# AeroCache 
It is a high-speed storage engine that keeps data in RAM rather than on a hard drive.
A high-performance, fault-tolerant, distributed in-memory key-value store written from scratch in Java, paired with a real-time observability dashboard built in Flutter. 

Built to replicate the core architectural concepts of enterprise caching systems like Memcached, Redis Cluster, and Amazon DynamoDB.

## System Architecture

*   **The Engine:** A thread-safe, $O(1)$ LRU Cache powered by a custom HashMap and Doubly Linked List.
*   **Time-to-Live (TTL):** $O(\log N)$ key expiration powered by a background Min-Heap daemon.
*   **The Routing Layer:** A custom TCP Load Balancer utilizing **Consistent Hashing (MD5)** to mathematically partition data across multiple physical nodes.
*   **Fault Tolerance:** Active node monitoring with automatic Ring rebalancing when a node goes offline.
*   **Elastic Scalability:** Dynamic injection of new cache servers without downtime, featuring a Background Data Rebalancing Protocol to migrate keys to new nodes.
*   **Observability:** A native macOS Flutter desktop application that connects to the router via TCP sockets to visualize the cluster topology and data partitioning in real-time.

## Tech Stack
*   **Backend:** Java 11+, TCP Sockets, `java.util.concurrent` (ExecutorServices, Thread Pools)
*   **Dashboard:** Flutter / Dart (macOS Desktop Native), `dart:io` Sockets
*   **Protocol:** Custom lightweight TCP text protocol

