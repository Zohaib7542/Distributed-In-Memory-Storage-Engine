import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'package:flutter/material.dart';

void main() {
  runApp(const AeroCacheApp());
}

class AeroCacheApp extends StatelessWidget {
  const AeroCacheApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'AeroCache Observability',
      theme: ThemeData.dark().copyWith(
        scaffoldBackgroundColor: const Color(0xFF1E1E2E),
        primaryColor: Colors.cyanAccent,
      ),
      home: const DashboardScreen(),
      debugShowCheckedModeBanner: false,
    );
  }
}

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  Socket? _socket;
  List<String> _activeNodes = [];
  bool _isConnected = false;
  Timer? _pollingTimer;

  @override
  void initState() {
    super.initState();
    _connectToServer();
  }

  Future<void> _connectToServer() async {
    try {
      // Connect to the Java CacheRouter on port 8080
      _socket = await Socket.connect('localhost', 8080);
      setState(() => _isConnected = true);

      // Listen for incoming data from the Java server
      _socket!.listen((List<int> data) {
        final response = utf8.decode(data).trim();
        _parseResponse(response);
      }, onDone: () {
        setState(() => _isConnected = false);
        _pollingTimer?.cancel();
      });

      // Start polling the server every 2 seconds
      _pollingTimer = Timer.periodic(const Duration(seconds: 2), (timer) {
        if (_isConnected) {
          _socket!.write('CLUSTER_STATUS\n');
        }
      });
    } catch (e) {
      print("Connection failed: $e");
      setState(() => _isConnected = false);
    }
  }

  void _parseResponse(String response) {
    // Expected format: ACTIVE NODES: [localhost:8081, localhost:8082]
    if (response.startsWith("ACTIVE NODES:")) {
      final listString = response.replaceAll("ACTIVE NODES: [", "").replaceAll("]", "");
      if (listString.isEmpty) {
        setState(() => _activeNodes = []);
        return;
      }
      setState(() {
        _activeNodes = listString.split(',').map((s) => s.trim()).toList();
        // Sort them so they don't randomly jump around the ring on every refresh
        _activeNodes.sort(); 
      });
    }
  }

  @override
  void dispose() {
    _pollingTimer?.cancel();
    _socket?.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('AeroCache Cluster Topology'),
        backgroundColor: const Color(0xFF181825),
        actions: [
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: Row(
              children: [
                Icon(
                  _isConnected ? Icons.check_circle : Icons.error,
                  color: _isConnected ? Colors.greenAccent : Colors.redAccent,
                ),
                const SizedBox(width: 8),
                Text(_isConnected ? "Connected to Router" : "Disconnected"),
              ],
            ),
          )
        ],
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text(
              "Consistent Hashing Ring",
              style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: Colors.white70),
            ),
            const SizedBox(height: 50),
            SizedBox(
              width: 400,
              height: 400,
              child: CustomPaint(
                painter: HashRingPainter(nodes: _activeNodes),
              ),
            ),
            const SizedBox(height: 50),
            Text(
              "Active Nodes: ${_activeNodes.length}",
              style: const TextStyle(fontSize: 18, color: Colors.cyanAccent),
            )
          ],
        ),
      ),
    );
  }
}

class HashRingPainter extends CustomPainter {
  final List<String> nodes;

  HashRingPainter({required this.nodes});

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.width / 2.5;

    // 1. Draw the Hash Ring
    final ringPaint = Paint()
      ..color = Colors.white12
      ..style = PaintingStyle.stroke
      ..strokeWidth = 4;
    canvas.drawCircle(center, radius, ringPaint);

    if (nodes.isEmpty) return;

    // 2. Draw the Nodes around the ring
    final nodePaint = Paint()
      ..color = Colors.cyanAccent
      ..style = PaintingStyle.fill;

    final textPainter = TextPainter(
      textAlign: TextAlign.center,
      textDirection: TextDirection.ltr,
    );

    final angleStep = (2 * pi) / nodes.length;

    for (int i = 0; i < nodes.length; i++) {
      // Calculate coordinates on the circle
      final angle = i * angleStep - (pi / 2); // Start at the top (12 o'clock)
      final x = center.dx + radius * cos(angle);
      final y = center.dy + radius * sin(angle);

      // Draw the server node dot
      canvas.drawCircle(Offset(x, y), 12, nodePaint);

      // Draw the server label (e.g., localhost:8081)
      textPainter.text = TextSpan(
        text: nodes[i],
        style: const TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.bold),
      );
      textPainter.layout();
      
      // Position the text slightly outside the dot
      final textX = x - (textPainter.width / 2) + (25 * cos(angle));
      final textY = y - (textPainter.height / 2) + (25 * sin(angle));
      
      textPainter.paint(canvas, Offset(textX, textY));
    }
  }

  @override
  bool shouldRepaint(covariant HashRingPainter oldDelegate) {
    return oldDelegate.nodes != nodes;
  }
}