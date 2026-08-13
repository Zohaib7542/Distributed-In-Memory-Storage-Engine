# AeroCache: System Architecture & Business Case

## 1. The Purpose of This Project
AeroCache is a highly available, distributed in-memory key-value store. Its primary purpose is to act as a high-speed data layer that sits in front of a traditional, slower database. By keeping frequently accessed data (like user sessions, gaming profiles, or real-time leaderboards) in RAM rather than on a hard drive, it delivers sub-millisecond read and write speeds.

## 2. The Problems It Solves
Modern web applications face three major infrastructure bottlenecks that AeroCache is designed to eliminate:

*   **Database Overload:** Reading from a disk-based database (like PostgreSQL or MySQL) is slow and expensive. If an app goes viral, millions of queries can crash the database. AeroCache absorbs this read-heavy traffic.
*   **The Scaling Ceiling:** Traditional servers can only be upgraded so much (Vertical Scaling). AeroCache solves this via Horizontal Scaling—allowing you to connect an infinite number of cheap, commodity servers together to pool their RAM.
*   **Cache Stampedes & Node Failures:** In basic caching systems, if a server crashes, all its data is lost, causing a massive spike in traffic to the main database. AeroCache solves this using Consistent Hashing, ensuring that a dead server only affects a tiny fraction of the data, while the rest of the cluster stays perfectly intact.

---

## 3. How It Works (The Architecture)

AeroCache is split into four distinct, decoupled layers:

### The Core Engine (Data Structures)
*   **$O(1)$ LRU Eviction:** The cache uses a combination of a Thread-Safe HashMap and a Doubly Linked List. This guarantees that whether you are searching for a key, adding a new key, or kicking out the oldest key to make room, the operation happens in constant time—instantly.
*   **$O(\log N)$ TTL Expiration:** Keys with a Time-To-Live (TTL) are tracked in a custom Min-Heap (Priority Queue). A background daemon thread safely monitors this heap, continuously pruning expired data without blocking the main event loop.

### The Network Layer (TCP & Concurrency)
*   Instead of heavy HTTP protocols, nodes communicate via raw **TCP Sockets** using a custom lightweight text protocol. 
*   Connections are managed via an **ExecutorService Thread Pool**, ensuring the system gracefully handles thousands of concurrent client connections without exhausting the JVM's memory.

### The Routing Layer (Distributed Systems)
*   A centralized `CacheRouter` acts as the load balancer. It uses an **MD5 Consistent Hashing Ring** to mathematically assign incoming keys to specific backend nodes.
*   **Auto-Healing:** If a backend node crashes, the Router catches the network exception, mathematically erases the node from the Hash Ring, and seamlessly re-routes traffic to the surviving nodes.

### The Observability Layer (Flutter)
*   A native macOS desktop application built in Dart/Flutter. It maintains a persistent TCP connection to the Router, polling cluster status to visualize node health, Hash Ring rebalancing, and system topology in real-time.

---

## 4. Why A Company Needs This (The Business Value)

In a commercial setting, deploying a system like AeroCache directly impacts the bottom line:

| Business Metric | How AeroCache Delivers It |
| :--- | :--- |
| **Cost Reduction** | Cloud databases charge per query. Intercepting 90% of reads via a free, in-memory RAM cache drastically lowers monthly AWS/GCP bills. |
| **High Availability (Uptime)** | The auto-healing Hash Ring ensures that if a server rack loses power, the application does not go down. The system routes around the damage. |
| **Elasticity** | Companies experience traffic spikes (e.g., Black Friday). AeroCache allows operations teams to inject new servers into the cluster live, without taking the system offline. |

---

## 5. The Future Roadmap (V2 Enhancements)

To evolve AeroCache into a production-ready enterprise tier equivalent to Redis or Cassandra, the following features are planned for the next iteration:

*   **Write-Ahead Logging (WAL):** Currently, if the entire data center loses power, RAM is wiped. Implementing a WAL will append every `SET` command to a local disk file, allowing a node to perfectly rebuild its memory state upon reboot.
*   **Data Replication:** Upgrading the Hash Ring so that every key is not just written to its primary node, but also silently duplicated to its next neighbor on the ring, ensuring zero data loss if a single node dies.
*   **Raft Consensus Algorithm:** Removing the centralized `CacheRouter` and allowing the backend nodes to talk to each other to elect a "Leader," removing the system's single point of failure.