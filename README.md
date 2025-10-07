# P2P File Sharing System

This project is a **Peer-to-Peer (P2P) file-sharing system**, inspired by **BitTorrent**, implemented in **Java**. It is part of the CNT5106C **Computer Networks (Spring 2025)**.

## 🚀 Features (Final Submission)

✔ Establishes P2P connections using **TCP sockets**

✔ Implements a **handshake process** for peer authentication

✔ Reads configuration files (`Common.cfg`, `PeerInfo.cfg`)

✔ Exchanges **Bitfield messages** to indicate file availability

✔ Implements **Interested** and **Not Interested** message exchange

✔ Maintains **TCP connections** between multiple peers

✔ Logs network activity, including peer interactions

✔ Uses a **bitfield** to track file pieces

✔ Initializes a **file system** for each peer (storing received pieces)

✔ Implements **file sharing** with piece request and response logic

✔ Implements **choking/unchoking logic** to prioritize download sources

✔ Reassembles the complete file once all pieces are received

✔ Handles **termination condition** when all peers have the complete file

## 🛠️ Getting Started

### **1️⃣ Setup Environment**

1. Install **JDK 17 or higher**.
2. Clone the repository:
   ```sh
   git clone https://github.com/anayy09/P2PFileSharing.git
   cd P2PFileSharing
   ```
3. Ensure the **configuration files** (`Common.cfg`, `PeerInfo.cfg`) are in the project root.

### **2️⃣ Compilation & Execution**

#### **Compile**

```sh
javac peerProcess.java
```

#### **Run a Peer**

Each peer is started separately by providing a unique **peer ID**:

```sh
java peerProcess 1001
```

```sh
java peerProcess 1002
```

```sh
java peerProcess 1003
```

## 📂 Project Structure

```
P2PFileSharing/
│── .gitignore
│── README.md
│── Common.cfg    # Common configuration (file size, piece size, etc.)
│── PeerInfo.cfg  # Peer information (IDs, ports, initial file ownership)
│── peerProcess.java  # Main peer-to-peer file-sharing logic
│── log/          # Logs for each peer's network activity
│── peer_1001/    # Directory for Peer 1001 (stores received pieces)
│── peer_1002/    # Directory for Peer 1002
│── peer_1003/
```

## 📝 Logging Events

The system logs:
✔ TCP connections (`connected`, `disconnected`)

✔ Bitfield exchange (`sent`, `received`)

✔ **Interested / Not Interested messages**

✔ File transfer status

✔ Choking/unchoking events

✔ Termination condition when all peers have the complete file

Example logs:

```
[Time]: Peer 1001 received the ‘interested’ message from Peer 1002.
[Time]: Peer 1001 sent the ‘not interested’ message to Peer 1003.
[Time]: Peer 1002 has downloaded the piece [4] from Peer 1001.
[Time]: Peer 1001 has downloaded the complete file.
```

## ❗ Known Issues (Final Submission)

🔹 **Rare Piece Selection Not Implemented** – The rarest-first strategy for piece selection is not yet implemented.

🔹 **No Dynamic Reconnection** – Peers do not retry connections dynamically if a peer becomes available later.
