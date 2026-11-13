import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _channel = MethodChannel('gametranslator/native');

const Map<String, String> _idiomas = {
  'en': 'Inglés',
  'es': 'Español',
  'fr': 'Francés',
  'de': 'Alemán',
  'it': 'Italiano',
  'pt': 'Portugués',
};

void main() => runApp(const TraductorApp());

class TraductorApp extends StatelessWidget {
  const TraductorApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Traductor de juegos',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: Colors.deepPurple, useMaterial3: true),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  bool _overlayOk = false;
  bool _running = false;
  bool _busy = false;
  String _source = 'en';
  String _target = 'es';
  String _mode = 'overlay';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _load() async {
    try {
      final s = Map<String, dynamic>.from(
          await _channel.invokeMethod('getSettings') as Map);
      setState(() {
        _source = s['source'] ?? 'en';
        _target = s['target'] ?? 'es';
        _mode = s['mode'] ?? 'overlay';
      });
    } catch (_) {}
    await _refresh();
  }

  Future<void> _refresh() async {
    final ok = await _channel.invokeMethod<bool>('hasOverlayPermission') ?? false;
    final run = await _channel.invokeMethod<bool>('isRunning') ?? false;
    if (mounted) {
      setState(() {
        _overlayOk = ok;
        _running = run;
      });
    }
  }

  Future<void> _save() => _channel.invokeMethod('saveSettings', {
        'source': _source,
        'target': _target,
        'mode': _mode,
      });

  void _msg(String text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  Future<void> _download() async {
    if (_source == _target) {
      _msg('Elige idiomas distintos');
      return;
    }
    setState(() => _busy = true);
    try {
      await _channel.invokeMethod(
          'downloadModel', {'source': _source, 'target': _target});
      _msg('Idiomas descargados ✔');
    } on PlatformException catch (e) {
      _msg('Error al descargar: ${e.message}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _toggle() async {
    if (_running) {
      await _channel.invokeMethod('stopOverlay');
    } else {
      if (_source == _target) {
        _msg('Elige idiomas distintos');
        return;
      }
      await _save();
      try {
        final ok = await _channel.invokeMethod<bool>('startOverlay') ?? false;
        if (!ok) _msg('Cancelaste el permiso de captura de pantalla');
      } on PlatformException catch (e) {
        _msg(e.message ?? 'Error al iniciar');
      }
    }
    await Future.delayed(const Duration(milliseconds: 700));
    await _refresh();
  }

  Widget _langDropdown(String value, ValueChanged<String> onChanged) {
    return DropdownButton<String>(
      isExpanded: true,
      value: value,
      items: _idiomas.entries
          .map((e) => DropdownMenuItem(value: e.key, child: Text(e.value)))
          .toList(),
      onChanged: _running
          ? null
          : (v) {
              if (v == null) return;
              setState(() => onChanged(v));
              _save();
            },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Traductor de juegos')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: ListTile(
              leading: Icon(
                _overlayOk ? Icons.check_circle : Icons.warning_amber_rounded,
                color: _overlayOk ? Colors.green : Colors.orange,
              ),
              title: const Text('1. Permiso para mostrar sobre otras apps'),
              subtitle: Text(_overlayOk ? 'Concedido' : 'Falta concederlo'),
              trailing: _overlayOk
                  ? null
                  : FilledButton(
                      onPressed: () =>
                          _channel.invokeMethod('requestOverlayPermission'),
                      child: const Text('Conceder'),
                    ),
            ),
          ),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('2. Idiomas',
                      style: TextStyle(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 8),
                  const Text('El juego está en:'),
                  _langDropdown(_source, (v) => _source = v),
                  const Text('Traducir a:'),
                  _langDropdown(_target, (v) => _target = v),
                  const SizedBox(height: 8),
                  OutlinedButton.icon(
                    onPressed: _busy || _running ? null : _download,
                    icon: _busy
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.download),
                    label: const Text('Descargar idiomas (necesita internet)'),
                  ),
                ],
              ),
            ),
          ),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('3. Cómo mostrar la traducción',
                      style: TextStyle(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 8),
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment(
                          value: 'overlay', label: Text('Sobre el texto')),
                      ButtonSegment(value: 'box', label: Text('Caja abajo')),
                    ],
                    selected: {_mode},
                    onSelectionChanged: _running
                        ? null
                        : (s) {
                            setState(() => _mode = s.first);
                            _save();
                          },
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 8),
          FilledButton.icon(
            onPressed: _overlayOk ? _toggle : null,
            style: FilledButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 18),
              backgroundColor: _running ? Colors.red : null,
            ),
            icon: Icon(_running ? Icons.stop : Icons.play_arrow),
            label: Text(
              _running ? 'Detener traductor' : 'Iniciar traductor flotante',
              style: const TextStyle(fontSize: 16),
            ),
          ),
          const SizedBox(height: 16),
          const Text(
            'Cómo se usa:\n'
            '• Pulsa Iniciar. Android preguntará qué compartir: elige "Toda la pantalla".\n'
            '• Abre tu juego. Verás un botón flotante "文A".\n'
            '• Púlsalo para traducir lo que se ve. Púlsalo otra vez (✕) para quitar la traducción.\n'
            '• Puedes arrastrar el botón a donde no moleste.',
            style: TextStyle(height: 1.5),
          ),
        ],
      ),
    );
  }
}

