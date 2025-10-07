import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class peerProcess {

    // Configuration constants
    private static final String COMMON_CFG_PATH = "Common.cfg";
    private static final String PEER_INFO_CFG_PATH = "PeerInfo.cfg";

    // Peer specific information
    private final int peerID;
    private String hostName;
    private int listeningPort;
    private boolean hasFileInitially;
    private BitSet bitfield;
    private final Map<Integer, PeerInfo> peerInfoMap = new ConcurrentHashMap<>();
    private final Map<Integer, PeerConnectionHandler> activeConnections = new ConcurrentHashMap<>();
    private final Set<Integer> interestedPeers = ConcurrentHashMap.newKeySet();
    private final Set<Integer> chokedPeers = ConcurrentHashMap.newKeySet(); // Peers choked by me
    private final Set<Integer> peersChokingMe = ConcurrentHashMap.newKeySet(); // Peers choking me
    private final Set<Integer> preferredNeighbors = ConcurrentHashMap.newKeySet();
    private volatile Integer optimisticallyUnchokedNeighbor = null;
    private final AtomicInteger downloadedPieces = new AtomicInteger(0);
    private final AtomicInteger totalPeersCompleted = new AtomicInteger(0);


    // Common configuration
    private int numberOfPreferredNeighbors;
    private int unchokingInterval; // seconds
    private int optimisticUnchokingInterval; // seconds
    private String fileName;
    private int fileSize;
    private int pieceSize;
    private int numberOfPieces;

    // File handling
    private byte[][] filePieces; // Stores actual file pieces

    // Logging
    private PrintWriter logger;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // Concurrency
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2); // For choking and optimistic unchoking tasks
    private final ExecutorService connectionExecutor = Executors.newCachedThreadPool();


    // --- Message Types ---
    private static final byte MSG_CHOKE = 0;
    private static final byte MSG_UNCHOKE = 1;
    private static final byte MSG_INTERESTED = 2;
    private static final byte MSG_NOT_INTERESTED = 3;
    private static final byte MSG_HAVE = 4;
    private static final byte MSG_BITFIELD = 5;
    private static final byte MSG_REQUEST = 6;
    private static final byte MSG_PIECE = 7;
    private static final byte MSG_HANDSHAKE = -1; // Special case for identification

    // --- PeerInfo Class ---
    private static class PeerInfo {
        int peerID;
        String hostName;
        int port;
        boolean hasFile;
        BitSet bitfield; // Track pieces the remote peer has
        volatile double downloadRate = 0; // Pieces per interval
        volatile long downloadStartTime = 0;
        volatile int piecesDownloadedInInterval = 0;


        PeerInfo(int peerID, String hostName, int port, boolean hasFile) {
            this.peerID = peerID;
            this.hostName = hostName;
            this.port = port;
            this.hasFile = hasFile;
        }

        @Override
        public String toString() {
            return "PeerInfo{" + "peerID=" + peerID + ", hostName='" + hostName + "'" + ", port=" + port + ", hasFile=" + hasFile + '}';
        }
    }

    // --- Constructor ---
    public peerProcess(int peerID) {
        this.peerID = peerID;
        try {
            // Create log file directory if it doesn't exist
            File logDir = new File("log_peer_" + peerID);
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            this.logger = new PrintWriter(new FileWriter("log_peer_" + peerID + "/log_peer_" + peerID + ".log"), true);
        } catch (IOException e) {
            System.err.println("Error creating log file for peer " + peerID);
            e.printStackTrace();
            System.exit(1);
        }
    }

    // --- Logging Utility ---
    private synchronized void log(String message) {
        String timestamp = dateFormat.format(new Date());
        logger.println(timestamp + ": Peer " + peerID + " " + message);
        // System.out.println(timestamp + ": Peer " + peerID + " " + message); // Optional: print to console too
    }

    // --- Configuration Loading ---
    private void loadCommonConfig() throws IOException {
        log("Loading Common.cfg");
        try (BufferedReader reader = new BufferedReader(new FileReader(COMMON_CFG_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\s+");
                if (parts.length < 2) continue;
                switch (parts[0]) {
                    case "NumberOfPreferredNeighbors":
                        numberOfPreferredNeighbors = Integer.parseInt(parts[1]);
                        break;
                    case "UnchokingInterval":
                        unchokingInterval = Integer.parseInt(parts[1]);
                        break;
                    case "OptimisticUnchokingInterval":
                        optimisticUnchokingInterval = Integer.parseInt(parts[1]);
                        break;
                    case "FileName":
                        fileName = parts[1];
                        break;
                    case "FileSize":
                        fileSize = Integer.parseInt(parts[1]);
                        break;
                    case "PieceSize":
                        pieceSize = Integer.parseInt(parts[1]);
                        break;
                }
            }
            numberOfPieces = (int) Math.ceil((double) fileSize / pieceSize);
            this.bitfield = new BitSet(numberOfPieces);
            log("Common config loaded: PreferredNeighbors=" + numberOfPreferredNeighbors +
                ", UnchokingInterval=" + unchokingInterval +
                ", OptimisticInterval=" + optimisticUnchokingInterval +
                ", FileName=" + fileName +
                ", FileSize=" + fileSize +
                ", PieceSize=" + pieceSize +
                ", NumPieces=" + numberOfPieces);
        }
    }

    private void loadPeerInfoConfig() throws IOException {
        log("Loading PeerInfo.cfg");
        try (BufferedReader reader = new BufferedReader(new FileReader(PEER_INFO_CFG_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\s+");
                if (parts.length < 4) continue;
                int id = Integer.parseInt(parts[0]);
                String host = parts[1];
                int port = Integer.parseInt(parts[2]);
                boolean hasFile = Integer.parseInt(parts[3]) == 1;

                PeerInfo info = new PeerInfo(id, host, port, hasFile);
                info.bitfield = new BitSet(numberOfPieces); // Initialize bitfield for each peer
                if (hasFile) {
                    info.bitfield.set(0, numberOfPieces); // If peer starts with file, set all bits
                    totalPeersCompleted.incrementAndGet(); // Count peers starting with the file
                }
                peerInfoMap.put(id, info);

                if (id == this.peerID) {
                    this.hostName = host;
                    this.listeningPort = port;
                    this.hasFileInitially = hasFile;
                    if (hasFile) {
                        this.bitfield.set(0, numberOfPieces);
                        log("Peer " + peerID + " starts with the complete file.");
                        loadInitialFilePieces();
                    } else {
                         // Initialize filePieces array even if file is not present initially
                        this.filePieces = new byte[numberOfPieces][];
                        log("Peer " + peerID + " starts without the file.");
                    }
                }
            }
             log("PeerInfo config loaded for " + peerInfoMap.size() + " peers.");
        }
    }

     // --- File Handling ---
    private void loadInitialFilePieces() {
        log("Loading initial file pieces from " + fileName);
        this.filePieces = new byte[numberOfPieces][];
        File file = new File("peer_" + peerID + "/" + fileName); // Assume file is in peer's directory
        if (!file.exists()) {
             log("Error: Initial file " + file.getPath() + " not found for peer " + peerID);
             // Attempt to find in project root as fallback (adjust path as needed)
             file = new File(fileName);
             if (!file.exists()) {
                 log("Error: Initial file " + fileName + " not found in project root either.");
                 System.err.println("Error: File " + fileName + " not found for peer " + peerID + " which should have it.");
                 System.exit(1);
             } else {
                 log("Found initial file in project root: " + file.getPath());
             }
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            for (int i = 0; i < numberOfPieces; i++) {
                int currentPieceSize = (i == numberOfPieces - 1) ? (fileSize - i * pieceSize) : pieceSize;
                byte[] pieceData = new byte[currentPieceSize];
                int bytesRead = fis.read(pieceData);
                 if (bytesRead != currentPieceSize) {
                    log("Warning: Read fewer bytes than expected for piece " + i + ". Read: " + bytesRead + ", Expected: " + currentPieceSize);
                    // Handle potential partial read if necessary, maybe resize pieceData
                    if (bytesRead > 0) {
                        filePieces[i] = Arrays.copyOf(pieceData, bytesRead);
                    } else {
                         filePieces[i] = new byte[0]; // Or handle error appropriately
                    }
                } else {
                    filePieces[i] = pieceData;
                }
            }
            log("Successfully loaded " + numberOfPieces + " pieces from " + file.getPath());
        } catch (IOException e) {
            log("Error reading initial file: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private synchronized void savePiece(int pieceIndex, byte[] data) {
        if (pieceIndex < 0 || pieceIndex >= numberOfPieces) {
            log("Error: Received invalid piece index " + pieceIndex);
            return;
        }
        if (filePieces[pieceIndex] == null) { // Only save if we don't have it
            filePieces[pieceIndex] = data;
            bitfield.set(pieceIndex);
            int currentPieceCount = downloadedPieces.incrementAndGet();
            log("Received and saved piece " + pieceIndex + ". Now have " + currentPieceCount + "/" + numberOfPieces + " pieces.");

            // Check if file download is complete
            if (currentPieceCount == numberOfPieces && !hasFileInitially) { // Avoid re-logging if started with file
                log("Has downloaded the complete file.");
                hasFileInitially = true; // Mark as having the file now
                totalPeersCompleted.incrementAndGet();
                reassembleFile();
                // Send NOT_INTERESTED to all peers? Or let choking handle it.
                // Consider sending have messages for the last piece.
            }

            // Broadcast HAVE message to all connected peers
             broadcastHaveMessage(pieceIndex);

             // Re-evaluate interest in peers based on the new piece
             evaluateInterestAfterHave();

        } else {
             log("Already have piece " + pieceIndex + ". Ignoring duplicate.");
        }
    }

     private void evaluateInterestAfterHave() {
        activeConnections.forEach((remotePeerId, handler) -> {
            PeerInfo remotePeerInfo = peerInfoMap.get(remotePeerId);
            if (remotePeerInfo != null) {
                boolean stillInterested = isInterestedIn(remotePeerInfo);
                // If we were interested but no longer are
                if (handler.amInterested && !stillInterested) {
                    handler.sendNotInterested();
                }
                // If we were not interested but now are (less common after getting a piece, but possible)
                else if (!handler.amInterested && stillInterested) {
                     handler.sendInterested();
                }
            }
        });
    }


    private void reassembleFile() {
        log("Reassembling the complete file: " + fileName);
        File outputFile = new File("peer_" + peerID + "/" + fileName);
        // Ensure parent directory exists
        outputFile.getParentFile().mkdirs();

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (int i = 0; i < numberOfPieces; i++) {
                if (filePieces[i] != null) {
                    fos.write(filePieces[i]);
                } else {
                    log("Error: Missing piece " + i + " during file reassembly.");
                    // Handle error - maybe request the missing piece again or fail
                    System.err.println("Critical Error: Missing piece " + i + " for peer " + peerID + " during final reassembly.");
                    return; // Stop reassembly
                }
            }
            log("File reassembly complete: " + outputFile.getPath());
        } catch (IOException e) {
            log("Error writing reassembled file: " + e.getMessage());
            e.printStackTrace();
        }
    }


    // --- Networking ---
    private void startServer() {
        connectionExecutor.submit(() -> {
            try (ServerSocket serverSocket = new ServerSocket(listeningPort)) {
                log("Server started. Listening on port " + listeningPort);
                while (!Thread.currentThread().isInterrupted() && !checkTerminationCondition()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        log("Accepted connection from " + clientSocket.getRemoteSocketAddress());
                        // Start a handler thread for the incoming connection
                        // We don't know the peerID yet, it will be identified via handshake
                        connectionExecutor.submit(new PeerConnectionHandler(clientSocket, this));
                    } catch (IOException e) {
                        if (serverSocket.isClosed()) {
                            log("Server socket closed, stopping listener.");
                            break;
                        }
                        log("Error accepting connection: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                log("Could not start server on port " + listeningPort + ": " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            } finally {
                 log("Server listener thread finished.");
            }
        });
    }

    private void connectToPeers() {
        log("Initiating connections to preceding peers...");
        for (PeerInfo info : peerInfoMap.values()) {
            if (info.peerID < this.peerID) {
                connectionExecutor.submit(() -> {
                    try {
                        log("Attempting to connect to peer " + info.peerID + " at " + info.hostName + ":" + info.port);
                        Socket socket = new Socket(info.hostName, info.port);
                        log("Successfully connected to peer " + info.peerID);
                        PeerConnectionHandler handler = new PeerConnectionHandler(socket, this, info.peerID);
                        activeConnections.put(info.peerID, handler);
                        handler.initiateHandshake(); // Send handshake immediately after connecting
                        connectionExecutor.submit(handler); // Start the handler's run loop
                    } catch (ConnectException e) {
                         log("Connection refused to peer " + info.peerID + ". It might not be running yet. Will retry later if needed.");
                         // Implement retry logic if necessary
                    } catch (UnknownHostException e) {
                        log("Unknown host: " + info.hostName + " for peer " + info.peerID);
                    } catch (IOException e) {
                        log("IOException connecting to peer " + info.peerID + ": " + e.getMessage());
                    }
                });
            }
        }
    }

    // --- Message Handling ---

    // Structure: Length (4 bytes) | Type (1 byte) | Payload (variable)
    private static class ActualMessage {
        int length;
        byte type;
        byte[] payload;

        ActualMessage(byte type, byte[] payload) {
            this.type = type;
            this.payload = (payload == null) ? new byte[0] : payload;
            this.length = this.payload.length + 1; // +1 for type byte
        }

         ActualMessage(byte type) {
            this(type, null);
        }

        byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(4 + length);
            buffer.putInt(length);
            buffer.put(type);
            buffer.put(payload);
            return buffer.array();
        }

        static ActualMessage fromBytes(byte[] data) {
             if (data == null || data.length < 5) { // 4 for length, 1 for type
                // Handle error: Invalid data length
                System.err.println("Error: Received invalid message data (too short)");
                return null; // Or throw exception
            }
            ByteBuffer buffer = ByteBuffer.wrap(data);
            int length = buffer.getInt();
             if (length < 1 || length > data.length - 4) { // Length must include type byte, cannot exceed remaining data
                 System.err.println("Error: Received message with invalid length field: " + length + ", data length: " + data.length);
                 return null;
             }
            byte type = buffer.get();
            byte[] payload = new byte[length - 1];
            if (payload.length > 0) {
                 if (buffer.remaining() < payload.length) {
                     System.err.println("Error: Buffer underflow. Remaining: " + buffer.remaining() + ", Payload expected: " + payload.length);
                     return null; // Avoid BufferUnderflowException
                 }
                buffer.get(payload);
            }
            return new ActualMessage(type, payload);
        }

         static ActualMessage fromStream(InputStream in) throws IOException {
            byte[] lengthBytes = new byte[4];
            int bytesRead = in.read(lengthBytes);
            if (bytesRead < 4) {
                // End of stream or incomplete read
                return null;
            }
            ByteBuffer lengthBuffer = ByteBuffer.wrap(lengthBytes);
            int length = lengthBuffer.getInt();

            if (length < 1) { // Message length must be at least 1 (for the type byte)
                 throw new IOException("Invalid message length received: " + length);
            }

            byte[] messageData = new byte[length]; // Read type + payload
            int totalPayloadRead = 0;
            while(totalPayloadRead < length) {
                int payloadRead = in.read(messageData, totalPayloadRead, length - totalPayloadRead);
                if (payloadRead == -1) {
                    throw new IOException("End of stream while reading message payload");
                }
                totalPayloadRead += payloadRead;
            }


            byte type = messageData[0];
            byte[] payload = (length > 1) ? Arrays.copyOfRange(messageData, 1, length) : new byte[0];

            return new ActualMessage(type, payload);
        }
    }


    // --- Handshake Message ---
    // Structure: Header (18 bytes) | Zero Bits (10 bytes) | PeerID (4 bytes)
    private static final String HANDSHAKE_HEADER = "P2PFILESHARINGPROJ";

    private byte[] createHandshakeMessage() {
        ByteBuffer buffer = ByteBuffer.allocate(32);
        buffer.put(HANDSHAKE_HEADER.getBytes(StandardCharsets.US_ASCII));
        buffer.put(new byte[10]); // Zero bits
        buffer.putInt(this.peerID);
        return buffer.array();
    }

    private static int validateHandshake(byte[] message, int expectedPeerID) {
        if (message == null || message.length != 32) {
            System.err.println("Invalid handshake length: " + (message == null ? "null" : message.length));
            return -1; // Invalid handshake
        }
        ByteBuffer buffer = ByteBuffer.wrap(message);
        byte[] headerBytes = new byte[18];
        buffer.get(headerBytes);
        String header = new String(headerBytes, StandardCharsets.US_ASCII);
        if (!HANDSHAKE_HEADER.equals(header)) {
             System.err.println("Invalid handshake header: " + header);
            return -1; // Invalid header
        }
        buffer.position(buffer.position() + 10); // Skip zero bits
        int receivedPeerID = buffer.getInt();

        // If expectedPeerID is 0, it means we accepted the connection and don't know who it is yet.
        // Return the receivedPeerID so the handler can identify the connection.
        if (expectedPeerID == 0) {
            return receivedPeerID;
        }

        // If we initiated the connection, check if the ID matches.
        if (receivedPeerID != expectedPeerID) {
             System.err.println("Handshake peer ID mismatch. Expected: " + expectedPeerID + ", Received: " + receivedPeerID);
            return -1; // Peer ID mismatch
        }
        return receivedPeerID; // Handshake valid
    }


    // --- Bitfield Handling ---
    private byte[] getBitfieldPayload() {
        // Ensure bitfield has the correct size before converting
        // toByteArray might return a shorter array if trailing bits are zero.
        // We need a fixed size based on numberOfPieces.
        int numBytes = (numberOfPieces + 7) / 8;
        byte[] bytes = new byte[numBytes];
        for (int i = 0; i < numberOfPieces; i++) {
            if (bitfield.get(i)) {
                bytes[i / 8] |= (1 << (7 - (i % 8)));
            }
        }
        return bytes;
    }

    private synchronized void updatePeerBitfield(int remotePeerID, byte[] payload) {
        PeerInfo info = peerInfoMap.get(remotePeerID);
        if (info != null) {
            BitSet receivedBitfield = BitSet.valueOf(payload);
             // The received payload might not cover all bits if trailing bits are 0.
             // Ensure the peer's bitfield reflects this correctly up to numberOfPieces.
             info.bitfield.clear(); // Clear existing bits first
             for (int i = 0; i < numberOfPieces; i++) {
                 // Check if the bit index is within the bounds implied by the payload length
                 int byteIndex = i / 8;
                 int bitInByteIndex = 7 - (i % 8);
                 if (byteIndex < payload.length && (payload[byteIndex] & (1 << bitInByteIndex)) != 0) {
                     info.bitfield.set(i);
                 }
             }

            log("Received bitfield from peer " + remotePeerID + ". Peer has " + info.bitfield.cardinality() + " pieces.");
            // Check if the remote peer has the complete file
            if (info.bitfield.cardinality() == numberOfPieces && !info.hasFile) {
                 info.hasFile = true;
                 totalPeersCompleted.incrementAndGet();
                 log("Peer " + remotePeerID + " reported having the complete file via bitfield. Total completed: " + totalPeersCompleted.get());
                 checkTerminationCondition();
            }

            // Now decide if we are interested in this peer
            if (isInterestedIn(info)) {
                activeConnections.get(remotePeerID).sendInterested();
            } else {
                activeConnections.get(remotePeerID).sendNotInterested();
            }
        } else {
             log("Warning: Received bitfield from unknown peer ID: " + remotePeerID);
        }
    }

    // --- Interest Check ---
    private synchronized boolean isInterestedIn(PeerInfo remotePeerInfo) {
        if (remotePeerInfo == null || remotePeerInfo.bitfield == null) {
            return false; // Cannot be interested if we don't know what they have
        }
        // Create a copy of the remote bitfield and AND it with the NOT of our bitfield
        BitSet remoteHas = (BitSet) remotePeerInfo.bitfield.clone();
        BitSet iNeed = (BitSet) this.bitfield.clone();
        iNeed.flip(0, numberOfPieces); // Flip bits to represent pieces I *don't* have

        remoteHas.and(iNeed); // Result has bits set only for pieces they have AND I need

        return !remoteHas.isEmpty(); // If the result is not empty, I am interested
    }

    // --- Piece Requesting ---
    private synchronized int selectPieceToRequest(int remotePeerID) {
        PeerInfo remotePeerInfo = peerInfoMap.get(remotePeerID);
        if (remotePeerInfo == null || remotePeerInfo.bitfield == null) {
            return -1; // Cannot request if we don't know the peer or what they have
        }

        BitSet theyHave = (BitSet) remotePeerInfo.bitfield.clone();
        BitSet iNeed = (BitSet) this.bitfield.clone();
        iNeed.flip(0, numberOfPieces); // Pieces I don't have

        theyHave.and(iNeed); // Pieces they have that I need

        if (theyHave.isEmpty()) {
            return -1; // No pieces they have that I need
        }

        // --- Random Selection Strategy ---
        List<Integer> availablePieces = new ArrayList<>();
        for (int i = theyHave.nextSetBit(0); i >= 0; i = theyHave.nextSetBit(i + 1)) {
             // Check if we are already requesting this piece from someone else (optional optimization)
             // For simplicity, we'll just pick randomly from the available set.
            availablePieces.add(i);
        }

        if (availablePieces.isEmpty()) {
            return -1;
        }

        // Randomly select one piece index
        int randomIndex = ThreadLocalRandom.current().nextInt(availablePieces.size());
        int chosenPiece = availablePieces.get(randomIndex);
        log("Selected piece " + chosenPiece + " to request from peer " + remotePeerID);
        return chosenPiece;

        // TODO: Implement rarest-first strategy if required
    }


    // --- Choking/Unchoking Logic ---
    private void startChokingTasks() {
        // Task to recalculate preferred neighbors
        scheduler.scheduleAtFixedRate(this::determinePreferredNeighbors,
                                      unchokingInterval, unchokingInterval, TimeUnit.SECONDS);

        // Task to recalculate optimistically unchoked neighbor
        scheduler.scheduleAtFixedRate(this::determineOptimisticNeighbor,
                                      optimisticUnchokingInterval, optimisticUnchokingInterval, TimeUnit.SECONDS);
         log("Scheduled choking tasks. UnchokingInterval=" + unchokingInterval + "s, OptimisticInterval=" + optimisticUnchokingInterval + "s");
    }

    private synchronized void determinePreferredNeighbors() {
         if (interestedPeers.isEmpty()) {
            log("No peers are interested, skipping preferred neighbor selection.");
            // Unchoke any previously preferred neighbors that are no longer preferred
            Set<Integer> previouslyPreferred = new HashSet<>(preferredNeighbors);
            previouslyPreferred.removeAll(Collections.emptySet()); // Clear preferredNeighbors effectively
            previouslyPreferred.forEach(peerId -> {
                PeerConnectionHandler handler = activeConnections.get(peerId);
                if (handler != null && !peerId.equals(optimisticallyUnchokedNeighbor)) { // Don't choke the optimistic one here
                    handler.sendChoke();
                }
            });
            preferredNeighbors.clear();
            return;
        }

        log("Determining preferred neighbors among " + interestedPeers.size() + " interested peers.");
        Set<Integer> newPreferredNeighbors = new HashSet<>();

        // If this peer has the complete file, select neighbors randomly from interested peers
        if (this.bitfield.cardinality() == numberOfPieces) {
            log("This peer has the complete file. Selecting preferred neighbors randomly.");
            List<Integer> interestedList = new ArrayList<>(interestedPeers);
            Collections.shuffle(interestedList);
            for (int i = 0; i < Math.min(numberOfPreferredNeighbors, interestedList.size()); i++) {
                newPreferredNeighbors.add(interestedList.get(i));
            }
        } else {
            // Select based on download rate (peers sending pieces to us)
            log("Selecting preferred neighbors based on download rate.");
            List<PeerInfo> candidates = interestedPeers.stream()
                .map(peerInfoMap::get)
                .filter(Objects::nonNull)
                 // Calculate rate based on pieces received in the last interval
                .peek(p -> {
                    long now = System.currentTimeMillis();
                    long elapsed = now - p.downloadStartTime;
                    if (elapsed > 0 && p.piecesDownloadedInInterval > 0) {
                        // Rate in pieces per second, scaled for comparison
                        p.downloadRate = (double) p.piecesDownloadedInInterval * 1000.0 / elapsed;
                    } else {
                        p.downloadRate = 0; // No downloads in this interval yet
                    }
                    // Reset for next interval AFTER calculation
                     // p.downloadStartTime = now; // Reset start time here or when piece received? Better when received.
                     // p.piecesDownloadedInInterval = 0; // Reset count here.
                })
                .sorted((p1, p2) -> Double.compare(p2.downloadRate, p1.downloadRate)) // Sort descending by rate
                .collect(Collectors.toList());

             // Reset download counts for the next interval AFTER sorting and selection
             candidates.forEach(p -> {
                 p.piecesDownloadedInInterval = 0;
                 // p.downloadStartTime = System.currentTimeMillis(); // Reset start time for next interval measurement
             });


            int count = 0;
            for (PeerInfo candidate : candidates) {
                if (count < numberOfPreferredNeighbors) {
                    newPreferredNeighbors.add(candidate.peerID);
                    log("Selected peer " + candidate.peerID + " as preferred neighbor (Rate: " + String.format("%.2f", candidate.downloadRate) + ")");
                    count++;
                } else {
                    break;
                }
            }
             // If fewer candidates than needed, add randomly from remaining interested peers (less likely if rates are calculated)
             if (count < numberOfPreferredNeighbors && interestedPeers.size() > count) {
                 List<Integer> remainingInterested = new ArrayList<>(interestedPeers);
                 remainingInterested.removeAll(newPreferredNeighbors);
                 Collections.shuffle(remainingInterested);
                 int needed = numberOfPreferredNeighbors - count;
                 for(int i=0; i < Math.min(needed, remainingInterested.size()); i++) {
                     int randomPeerId = remainingInterested.get(i);
                     newPreferredNeighbors.add(randomPeerId);
                     log("Selected peer " + randomPeerId + " randomly to fill preferred neighbor slots.");
                 }
             }
        }


        // --- Update Choke/Unchoke Status ---
        Set<Integer> previouslyPreferred = new HashSet<>(preferredNeighbors);

        // Unchoke new preferred neighbors (if they were choked)
        for (Integer peerId : newPreferredNeighbors) {
            if (!previouslyPreferred.contains(peerId) || chokedPeers.contains(peerId)) { // Also unchoke if it was previously preferred but somehow got choked
                PeerConnectionHandler handler = activeConnections.get(peerId);
                if (handler != null) {
                    log("Unchoking new preferred neighbor: " + peerId);
                    handler.sendUnchoke();
                }
            }
        }

        // Choke previously preferred neighbors who are no longer preferred
        // (unless they are the optimistically unchoked one)
        for (Integer peerId : previouslyPreferred) {
            if (!newPreferredNeighbors.contains(peerId) && !peerId.equals(optimisticallyUnchokedNeighbor)) {
                PeerConnectionHandler handler = activeConnections.get(peerId);
                if (handler != null) {
                     log("Choking previously preferred neighbor (no longer preferred): " + peerId);
                    handler.sendChoke();
                }
            }
        }

        // Update the set of preferred neighbors
        preferredNeighbors.clear();
        preferredNeighbors.addAll(newPreferredNeighbors);
        log("Preferred neighbors updated: " + preferredNeighbors);
    }

    private synchronized void determineOptimisticNeighbor() {
        log("Determining optimistically unchoked neighbor.");

        // Find interested peers that are currently choked (and not preferred)
        List<Integer> candidates = interestedPeers.stream()
            .filter(peerId -> chokedPeers.contains(peerId))
            .filter(peerId -> !preferredNeighbors.contains(peerId))
            .collect(Collectors.toList());

        Integer previouslyOptimistic = optimisticallyUnchokedNeighbor;

        if (candidates.isEmpty()) {
            log("No choked, interested, non-preferred peers to select for optimistic unchoking.");
            optimisticallyUnchokedNeighbor = null; // No one to select
        } else {
            // Select one randomly
            int randomIndex = ThreadLocalRandom.current().nextInt(candidates.size());
            optimisticallyUnchokedNeighbor = candidates.get(randomIndex);
            log("Selected peer " + optimisticallyUnchokedNeighbor + " as new optimistically unchoked neighbor.");

            // Unchoke the newly selected peer
            PeerConnectionHandler handler = activeConnections.get(optimisticallyUnchokedNeighbor);
            if (handler != null) {
                handler.sendUnchoke();
            }
        }

        // If there was a previously optimistic neighbor, and they are different from the new one,
        // and they are not now a preferred neighbor, choke them.
        if (previouslyOptimistic != null &&
            !previouslyOptimistic.equals(optimisticallyUnchokedNeighbor) &&
            !preferredNeighbors.contains(previouslyOptimistic))
        {
            PeerConnectionHandler handler = activeConnections.get(previouslyOptimistic);
            if (handler != null) {
                 log("Choking previously optimistically unchoked neighbor: " + previouslyOptimistic);
                handler.sendChoke();
            }
        }
         log("Optimistically unchoked neighbor updated: " + optimisticallyUnchokedNeighbor);
    }


    // --- Termination Check ---
    private boolean checkTerminationCondition() {
        // Condition: All peers (including self) have the complete file.
        if (totalPeersCompleted.get() == peerInfoMap.size()) {
             log("Termination condition met: All " + peerInfoMap.size() + " peers have the complete file.");
             return true;
        }
        return false;
    }

    private void shutdown() {
        log("Initiating shutdown sequence.");

        // 1. Stop scheduled tasks
        scheduler.shutdownNow();
        log("Choking scheduler shut down.");

        // 2. Close all active connections gracefully
        log("Closing active connections...");
        activeConnections.forEach((peerId, handler) -> {
            handler.closeConnection("System shutdown");
        });
        activeConnections.clear(); // Clear the map

        // 3. Shutdown connection executor
        connectionExecutor.shutdown();
        try {
            if (!connectionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                connectionExecutor.shutdownNow();
                log("Connection executor did not terminate gracefully, forced shutdown.");
            } else {
                 log("Connection executor shut down.");
            }
        } catch (InterruptedException e) {
            connectionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 4. Close logger
        if (logger != null) {
            log("Closing log file.");
            logger.close();
            logger = null; // Prevent further logging attempts
        }

        System.out.println("Peer " + peerID + " shutting down.");
        System.exit(0); // Consider if a clean exit from main is better
    }


     // --- Broadcast Methods ---
    private void broadcastHaveMessage(int pieceIndex) {
        log("Broadcasting HAVE message for piece " + pieceIndex + " to all connected peers.");
        ActualMessage haveMsg = new ActualMessage(MSG_HAVE, ByteBuffer.allocate(4).putInt(pieceIndex).array());
        byte[] haveMsgBytes = haveMsg.toBytes();
        activeConnections.values().forEach(handler -> handler.sendMessage(haveMsgBytes));
    }


    // --- Main Execution Logic ---
    public void run() {
        try {
            loadCommonConfig();
            loadPeerInfoConfig();

            // Check if peer ID was found in config
            if (this.hostName == null) {
                 System.err.println("Error: Peer ID " + peerID + " not found in " + PEER_INFO_CFG_PATH);
                 System.exit(1);
            }

            log("Peer " + peerID + " starting up at " + hostName + ":" + listeningPort);
            log("File: " + fileName + ", Size: " + fileSize + ", Pieces: " + numberOfPieces);

            startServer();
            connectToPeers();
            startChokingTasks();

            // Keep main thread alive to check for termination condition
            while (true) {
                 try {
                    Thread.sleep(5000); // Check every 5 seconds
                 } catch (InterruptedException e) {
                     log("Main thread interrupted, initiating shutdown.");
                     Thread.currentThread().interrupt(); // Preserve interrupt status
                     break; // Exit loop to shutdown
                 }

                 if (checkTerminationCondition()) {
                     log("All peers completed. Waiting a bit before shutdown...");
                     try {
                         Thread.sleep(5000); // Wait a short period to ensure messages propagate
                     } catch (InterruptedException e) {
                         Thread.currentThread().interrupt();
                     }
                     break; // Exit loop to shutdown
                 }
            }

        } catch (IOException e) {
            log("Initialization error: " + e.getMessage());
            e.printStackTrace();
        } finally {
             shutdown();
        }
    }


    // --- PeerConnectionHandler Inner Class ---
    private class PeerConnectionHandler implements Runnable {
        private final Socket socket;
        private final peerProcess parentPeer;
        private DataInputStream in;
        private DataOutputStream out;
        private volatile int remotePeerID = 0; // 0 initially, identified by handshake
        private volatile boolean connectionEstablished = false;
        private volatile boolean remotePeerChokingMe = true; // Assume choked initially
        private volatile boolean iAmChokingRemotePeer = true; // Assume choked initially
        private volatile boolean amInterested = false; // Am I interested in the remote peer?
        private volatile boolean isInterested = false; // Is the remote peer interested in me?

        // Constructor for incoming connections (peerID unknown initially)
        public PeerConnectionHandler(Socket socket, peerProcess parentPeer) {
            this.socket = socket;
            this.parentPeer = parentPeer;
            try {
                this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            } catch (IOException e) {
                parentPeer.log("Error creating streams for connection " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
                closeConnection("Stream creation failed");
            }
        }

        // Constructor for outgoing connections (peerID known)
        public PeerConnectionHandler(Socket socket, peerProcess parentPeer, int remotePeerID) {
            this(socket, parentPeer);
            this.remotePeerID = remotePeerID;
        }

        public void initiateHandshake() {
            if (this.remotePeerID != 0) { // Only for outgoing connections
                parentPeer.log("Sending handshake to peer " + this.remotePeerID);
                sendMessage(parentPeer.createHandshakeMessage());
            } else {
                 parentPeer.log("Cannot initiate handshake for incoming connection before ID is known.");
            }
        }

        private void processHandshake(byte[] handshakeMsg) {
            int receivedPeerID = validateHandshake(handshakeMsg, this.remotePeerID);

            if (receivedPeerID == -1) {
                parentPeer.log("Invalid handshake received from " + socket.getRemoteSocketAddress() + ". Closing connection.");
                closeConnection("Invalid handshake");
                return;
            }

            if (this.remotePeerID == 0) { // Incoming connection, handshake identifies the peer
                this.remotePeerID = receivedPeerID;
                // Check if connection already exists (e.g., simultaneous connection attempt)
                if (parentPeer.activeConnections.containsKey(this.remotePeerID)) {
                     parentPeer.log("Duplicate connection detected with peer " + this.remotePeerID + ". Closing this one.");
                     closeConnection("Duplicate connection");
                     return;
                }
                 parentPeer.activeConnections.put(this.remotePeerID, this); // Register the connection
                 parentPeer.log("Handshake successful with incoming peer " + this.remotePeerID);
                 // Send handshake back
                 parentPeer.log("Sending handshake reply to peer " + this.remotePeerID);
                 sendMessage(parentPeer.createHandshakeMessage());

            } else { // Outgoing connection, handshake confirms the peer
                 parentPeer.log("Handshake successful with outgoing peer " + this.remotePeerID);
            }

            connectionEstablished = true;
            parentPeer.chokedPeers.add(this.remotePeerID); // Initially choke the peer
            parentPeer.peersChokingMe.add(this.remotePeerID); // Assume they choke us initially

            // Send bitfield immediately after successful handshake
            sendBitfield();
        }

        private void sendBitfield() {
             synchronized(parentPeer.bitfield) { // Synchronize access to parent's bitfield
                byte[] payload = parentPeer.getBitfieldPayload();
                if (payload.length > 0) {
                    parentPeer.log("Sending BITFIELD to peer " + remotePeerID);
                    ActualMessage bitfieldMsg = new ActualMessage(MSG_BITFIELD, payload);
                    sendMessage(bitfieldMsg.toBytes());
                } else {
                     parentPeer.log("Skipping BITFIELD message to " + remotePeerID + " (bitfield is empty or error).");
                }
             }
        }

        public void sendInterested() {
            if (!amInterested) {
                parentPeer.log("Sending INTERESTED to peer " + remotePeerID);
                ActualMessage interestedMsg = new ActualMessage(MSG_INTERESTED);
                sendMessage(interestedMsg.toBytes());
                amInterested = true;
            }
        }

        public void sendNotInterested() {
             if (amInterested) {
                parentPeer.log("Sending NOT_INTERESTED to peer " + remotePeerID);
                ActualMessage notInterestedMsg = new ActualMessage(MSG_NOT_INTERESTED);
                sendMessage(notInterestedMsg.toBytes());
                amInterested = false;
             }
        }

         public void sendChoke() {
            if (!iAmChokingRemotePeer) {
                parentPeer.log("Sending CHOKE to peer " + remotePeerID);
                ActualMessage chokeMsg = new ActualMessage(MSG_CHOKE);
                sendMessage(chokeMsg.toBytes());
                iAmChokingRemotePeer = true;
                parentPeer.chokedPeers.add(remotePeerID); // Track that we are choking them
            }
        }

        public void sendUnchoke() {
             if (iAmChokingRemotePeer) {
                parentPeer.log("Sending UNCHOKE to peer " + remotePeerID);
                ActualMessage unchokeMsg = new ActualMessage(MSG_UNCHOKE);
                sendMessage(unchokeMsg.toBytes());
                iAmChokingRemotePeer = false;
                parentPeer.chokedPeers.remove(remotePeerID); // Track that we are not choking them
             }
        }

        private void sendRequest(int pieceIndex) {
             parentPeer.log("Sending REQUEST for piece " + pieceIndex + " to peer " + remotePeerID);
             ActualMessage requestMsg = new ActualMessage(MSG_REQUEST, ByteBuffer.allocate(4).putInt(pieceIndex).array());
             sendMessage(requestMsg.toBytes());
             // TODO: Track requested pieces to avoid duplicates and handle timeouts
        }

        private void sendPiece(int pieceIndex) {
            byte[] pieceData;
            synchronized (parentPeer.filePieces) { // Synchronize access to file pieces
                 if (pieceIndex < 0 || pieceIndex >= parentPeer.numberOfPieces || parentPeer.filePieces[pieceIndex] == null) {
                    parentPeer.log("Cannot send piece " + pieceIndex + " to peer " + remotePeerID + ": Piece not available.");
                    return;
                }
                pieceData = parentPeer.filePieces[pieceIndex];
            }

            parentPeer.log("Sending PIECE " + pieceIndex + " (" + pieceData.length + " bytes) to peer " + remotePeerID);
            ByteBuffer payloadBuffer = ByteBuffer.allocate(4 + pieceData.length);
            payloadBuffer.putInt(pieceIndex);
            payloadBuffer.put(pieceData);
            ActualMessage pieceMsg = new ActualMessage(MSG_PIECE, payloadBuffer.array());
            sendMessage(pieceMsg.toBytes());
        }


        public synchronized void sendMessage(byte[] msg) {
            if (out == null || socket.isClosed()) {
                parentPeer.log("Cannot send message to peer " + remotePeerID + ", connection closed.");
                return;
            }
            try {
                out.write(msg);
                out.flush();
            } catch (IOException e) {
                parentPeer.log("Error sending message to peer " + remotePeerID + ": " + e.getMessage());
                closeConnection("Send error");
            }
        }

        @Override
        public void run() {
            try {
                // --- Handshake Phase ---
                byte[] handshakeBuffer = new byte[32];
                int bytesRead = in.read(handshakeBuffer);
                if (bytesRead != 32) {
                    parentPeer.log("Failed to read complete handshake from " + socket.getRemoteSocketAddress() + ". Read: " + bytesRead);
                    closeConnection("Incomplete handshake");
                    return;
                }
                processHandshake(handshakeBuffer);

                if (!connectionEstablished) {
                    // Handshake failed or duplicate connection, already closed.
                    return;
                }

                // --- Message Loop ---
                while (connectionEstablished && !Thread.currentThread().isInterrupted()) {
                    // Read message length
                    int length;
                    try {
                         length = in.readInt(); // Reads 4 bytes for length
                         if (length < 1) {
                             parentPeer.log("Received invalid message length " + length + " from peer " + remotePeerID + ". Closing connection.");
                             closeConnection("Invalid message length");
                             break;
                         }
                    } catch (EOFException e) {
                         parentPeer.log("Peer " + remotePeerID + " closed the connection (EOF reading length).");
                         closeConnection("Remote peer disconnected");
                         break;
                    } catch (IOException e) {
                         parentPeer.log("IOException reading message length from peer " + remotePeerID + ": " + e.getMessage());
                         closeConnection("Read error");
                         break;
                    }


                    // Read message type and payload
                    byte[] messageBody = new byte[length]; // type (1) + payload (length-1)
                    try {
                        in.readFully(messageBody); // Read the exact number of bytes for type + payload
                    } catch (EOFException e) {
                         parentPeer.log("Peer " + remotePeerID + " closed the connection (EOF reading body).");
                         closeConnection("Remote peer disconnected");
                         break;
                    } catch (IOException e) {
                         parentPeer.log("IOException reading message body from peer " + remotePeerID + ": " + e.getMessage());
                         closeConnection("Read error");
                         break;
                    }

                    byte type = messageBody[0];
                    byte[] payload = (length > 1) ? Arrays.copyOfRange(messageBody, 1, length) : new byte[0];

                    processMessage(new ActualMessage(type, payload));

                     // Check termination after processing message
                     if (parentPeer.checkTerminationCondition()) {
                         parentPeer.log("Termination condition met while handling messages for peer " + remotePeerID + ". Handler stopping.");
                         // No need to close connection here, shutdown() will handle it.
                         break;
                     }
                }

            } catch (IOException e) {
                if (!socket.isClosed()) { // Avoid logging error if we closed it intentionally
                    parentPeer.log("IOException in connection handler for peer " + remotePeerID + ": " + e.getMessage());
                }
            } finally {
                closeConnection("Handler finished");
                 parentPeer.log("Connection handler thread for peer " + remotePeerID + " finished.");
            }
        }

        private void processMessage(ActualMessage msg) {
             if (msg == null) {
                 parentPeer.log("Received null message from peer " + remotePeerID + ". Ignoring.");
                 return;
             }
            switch (msg.type) {
                case MSG_CHOKE:
                    parentPeer.log("Received CHOKE from peer " + remotePeerID);
                    remotePeerChokingMe = true;
                    parentPeer.peersChokingMe.add(remotePeerID);
                    // Stop requesting pieces from this peer? (Handled by not requesting if choked)
                    break;
                case MSG_UNCHOKE:
                    parentPeer.log("Received UNCHOKE from peer " + remotePeerID);
                    remotePeerChokingMe = false;
                    parentPeer.peersChokingMe.remove(remotePeerID);
                    // Now we can request pieces if interested
                    requestPieceIfNeeded();
                    break;
                case MSG_INTERESTED:
                    parentPeer.log("Received INTERESTED from peer " + remotePeerID);
                    isInterested = true;
                    parentPeer.interestedPeers.add(remotePeerID);
                    break;
                case MSG_NOT_INTERESTED:
                    parentPeer.log("Received NOT_INTERESTED from peer " + remotePeerID);
                    isInterested = false;
                    parentPeer.interestedPeers.remove(remotePeerID);
                    // If they become not interested, we might consider choking them even if preferred?
                    // Current logic handles this: if not interested, they won't be selected based on download rate.
                    break;
                case MSG_HAVE:
                    if (msg.payload.length == 4) {
                        int pieceIndex = ByteBuffer.wrap(msg.payload).getInt();
                        parentPeer.log("Received HAVE for piece " + pieceIndex + " from peer " + remotePeerID);
                        PeerInfo remoteInfo = parentPeer.peerInfoMap.get(remotePeerID);
                        if (remoteInfo != null) {
                             synchronized(remoteInfo.bitfield) { // Synchronize access
                                remoteInfo.bitfield.set(pieceIndex);
                                // Check if remote peer now has the complete file
                                if (remoteInfo.bitfield.cardinality() == parentPeer.numberOfPieces && !remoteInfo.hasFile) {
                                    remoteInfo.hasFile = true;
                                    parentPeer.totalPeersCompleted.incrementAndGet();
                                    parentPeer.log("Peer " + remotePeerID + " completed download (inferred from HAVE). Total completed: " + parentPeer.totalPeersCompleted.get());
                                    parentPeer.checkTerminationCondition(); // Check if this completion triggers termination
                                }
                             }
                            // Decide if this new piece makes us interested
                            if (!amInterested && parentPeer.isInterestedIn(remoteInfo)) {
                                sendInterested();
                            }
                        }
                    } else {
                         parentPeer.log("Received malformed HAVE message from peer " + remotePeerID);
                    }
                    break;
                case MSG_BITFIELD:
                    parentPeer.log("Received BITFIELD from peer " + remotePeerID);
                    parentPeer.updatePeerBitfield(remotePeerID, msg.payload);
                    break;
                case MSG_REQUEST:
                     if (msg.payload.length == 4) {
                        int requestedIndex = ByteBuffer.wrap(msg.payload).getInt();
                        parentPeer.log("Received REQUEST for piece " + requestedIndex + " from peer " + remotePeerID);
                        // Check if we are choking this peer OR if they are not the optimistically unchoked one (if applicable)
                        if (!iAmChokingRemotePeer) { // Only send if not choking them (preferred or optimistically unchoked)
                             sendPiece(requestedIndex);
                        } else {
                             parentPeer.log("Ignoring REQUEST from choked peer " + remotePeerID);
                        }
                     } else {
                          parentPeer.log("Received malformed REQUEST message from peer " + remotePeerID);
                     }
                    break;
                case MSG_PIECE:
                    if (msg.payload.length > 4) {
                        ByteBuffer buffer = ByteBuffer.wrap(msg.payload);
                        int pieceIndex = buffer.getInt();
                        byte[] pieceData = new byte[msg.payload.length - 4];
                        buffer.get(pieceData);
                        parentPeer.log("Received PIECE " + pieceIndex + " (" + pieceData.length + " bytes) from peer " + remotePeerID);

                        // Record download for rate calculation
                        PeerInfo remoteInfo = parentPeer.peerInfoMap.get(remotePeerID);
                        if (remoteInfo != null) {
                             synchronized(remoteInfo) { // Synchronize access to PeerInfo download stats
                                if (remoteInfo.downloadStartTime == 0) { // Start timer on first piece received in an interval
                                    remoteInfo.downloadStartTime = System.currentTimeMillis();
                                }
                                remoteInfo.piecesDownloadedInInterval++;
                             }
                        }

                        parentPeer.savePiece(pieceIndex, pieceData);

                        // After receiving a piece, request another if still interested and unchoked
                        requestPieceIfNeeded();

                    } else {
                         parentPeer.log("Received malformed PIECE message from peer " + remotePeerID);
                    }
                    break;
                default:
                    parentPeer.log("Received unknown message type " + msg.type + " from peer " + remotePeerID);
            }
        }

        // Helper to request a piece if conditions are met
        private void requestPieceIfNeeded() {
             if (amInterested && !remotePeerChokingMe) {
                 int pieceToRequest = parentPeer.selectPieceToRequest(remotePeerID);
                 if (pieceToRequest != -1) {
                     sendRequest(pieceToRequest);
                 } else {
                      // We are interested, but they don't have any pieces we need right now.
                      // Or we already have all pieces.
                      parentPeer.log("No suitable piece to request from peer " + remotePeerID + " currently.");
                      // Maybe send NOT_INTERESTED if we completed the file?
                      if (parentPeer.bitfield.cardinality() == parentPeer.numberOfPieces) {
                          sendNotInterested();
                      }
                 }
             }
        }


        public synchronized void closeConnection(String reason) {
            if (!connectionEstablished && remotePeerID == 0 && socket != null && !socket.isClosed()) {
                 // Handle case where connection closes before handshake completes (e.g., duplicate connection)
                 parentPeer.log("Closing pre-handshake connection with " + socket.getRemoteSocketAddress() + ". Reason: " + reason);
                 try { socket.close(); } catch (IOException e) { /* Ignore */ }
                 return; // Exit early, no need to remove from maps etc.
            }

            if (!connectionEstablished) return; // Avoid closing multiple times or if never fully established

            connectionEstablished = false; // Mark as not established first
            parentPeer.log("Closing connection with peer " + remotePeerID + ". Reason: " + reason);

            // Remove from parent's tracking collections
            parentPeer.activeConnections.remove(remotePeerID);
            parentPeer.interestedPeers.remove(remotePeerID);
            parentPeer.chokedPeers.remove(remotePeerID);
            parentPeer.peersChokingMe.remove(remotePeerID);
            parentPeer.preferredNeighbors.remove(remotePeerID);
            if (Integer.valueOf(remotePeerID).equals(parentPeer.optimisticallyUnchokedNeighbor)) {
                parentPeer.optimisticallyUnchokedNeighbor = null;
            }


            try {
                if (in != null) in.close();
            } catch (IOException e) {
                // parentPeer.log("Error closing input stream for peer " + remotePeerID + ": " + e.getMessage());
            }
            try {
                if (out != null) out.close();
            } catch (IOException e) {
                 // parentPeer.log("Error closing output stream for peer " + remotePeerID + ": " + e.getMessage());
            }
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException e) {
                 // parentPeer.log("Error closing socket for peer " + remotePeerID + ": " + e.getMessage());
            }
             parentPeer.log("Connection resources released for peer " + remotePeerID);
        }
    }


    // --- Main Method ---
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java peerProcess <peerID>");
            System.exit(1);
        }

        int peerID;
        try {
            peerID = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Error: peerID must be an integer.");
            System.exit(1);
            return; // Keep compiler happy
        }

        peerProcess peer = new peerProcess(peerID);
        peer.run(); // Start the peer process
    }
}
