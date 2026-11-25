const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const { randomBytes } = require('node:crypto');
const { join, dirname } = require('node:path');
const mediasoup = require('mediasoup');
const trace = text => fs.appendFileSync(join(__dirname, 'trace.txt'), text + '\n');

function opusPackets(ogg) {
  const packets = [];
  let parts = [];
  for (let page = 0; page < ogg.length;) {
    if (ogg.toString('ascii', page, page + 4) !== 'OggS') throw new Error('Invalid Ogg page');
    const segments = ogg[page + 26];
    let data = page + 27 + segments;
    for (let i = 0; i < segments; i++) {
      const length = ogg[page + 27 + i];
      parts.push(ogg.subarray(data, data += length));
      if (length < 255) {
        packets.push(Buffer.concat(parts));
        parts = [];
      }
    }
    page = data;
  }
  return packets.slice(2, -1);
}

async function main() {
  const candidates = Object.entries(os.networkInterfaces()).flatMap(([name, entries]) =>
    /^(wlan|ap|swlan|softap)/.test(name) ? entries : []);
  const addresses = [...new Set(candidates.filter(entry => !entry.internal && entry.family === 'IPv4').map(entry => entry.address))];
  if (addresses.length !== 1) throw new Error('Expected exactly one Wi-Fi/hotspot IPv4 address: ' + addresses.join(', '));
  const [ip] = addresses;
  const token = randomBytes(18).toString('hex');
  const route = '/' + token;
  process.env.LD_LIBRARY_PATH = dirname(process.argv[2]);
  const worker = await mediasoup.createWorker({ workerBin: process.argv[2] });
  const router = await worker.createRouter({
    mediaCodecs: [{ kind: 'audio', mimeType: 'audio/opus', clockRate: 48000, channels: 2 }],
  });
  const direct = await router.createDirectTransport();
  const producer = await direct.produce({
    kind: 'audio',
    rtpParameters: {
      codecs: [{ mimeType: 'audio/opus', payloadType: 111, clockRate: 48000, channels: 2 }],
      encodings: [{ ssrc: 650001 }],
    },
  });
  const tone = opusPackets(fs.readFileSync(join(__dirname, 'tone.ogg')));
  let seq = 0;
  const timer = setInterval(() => {
    const packet = Buffer.alloc(12);
    packet[0] = 0x80;
    packet[1] = 111;
    packet.writeUInt16BE(seq & 0xffff, 2);
    packet.writeUInt32BE((seq * 960) >>> 0, 4);
    packet.writeUInt32BE(650001, 8);
    producer.send(Buffer.concat([packet, tone[seq % tone.length]]));
    seq++;
  }, 20);
  const server = http.createServer(async (req, res) => {
    const json = data => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(data)); };
    try {
      if (req.method === 'GET' && req.url === route) {
        res.setHeader('Content-Type', 'text/html; charset=utf-8');
        return res.end(fs.readFileSync(join(__dirname, 'tone.html')));
      }
      if (req.method === 'GET' && req.url === route + '/client.js') {
        res.setHeader('Content-Type', 'text/javascript');
        return res.end(fs.readFileSync(join(__dirname, 'client.js')));
      }
      if (req.method !== 'POST' || req.url !== route + '/rpc') { res.statusCode = 404; return res.end(); }
      let input = '';
      for await (const chunk of req) {
        input += chunk;
        if (input.length > 65536) { res.statusCode = 413; return res.end(); }
      }
      const data = JSON.parse(input);
      if (data.type === 'caps') return json(router.rtpCapabilities);
      if (data.type === 'transport') {
        if (transport) { res.statusCode = 409; return res.end('One listener per probe'); }
        transport = await router.createWebRtcTransport({ listenInfos: [{ ip, protocol: 'udp' }] });
        transport.on('icestatechange', state => trace('ICE ' + state));
        transport.on('dtlsstatechange', state => trace('DTLS ' + state));
        return json({ id: transport.id, iceParameters: transport.iceParameters, iceCandidates: transport.iceCandidates, dtlsParameters: transport.dtlsParameters });
      }
      if (data.type === 'connect') { await transport.connect({ dtlsParameters: data.parameters }); return json({}); }
      if (data.type === 'consume') {
        consumer = await transport.consume({ producerId: producer.id, rtpCapabilities: data.capabilities, paused: true });
        return json({ id: consumer.id, producerId: producer.id, kind: consumer.kind, rtpParameters: consumer.rtpParameters });
      }
      if (data.type === 'resume') { await consumer.resume(); trace('Consumer resumed'); return json({}); }
      res.statusCode = 400;
      res.end();
    } catch (error) {
      trace(error.message);
      res.statusCode = 400;
      res.end('Probe request failed');
    }
  });
  let transport;
  let consumer;
  server.listen(18765, ip, () => trace('JOIN http://' + ip + ':18765' + route));
  server.on('error', error => { trace(error.message); clearInterval(timer); worker.close(); process.exitCode = 1; });
  setTimeout(() => { clearInterval(timer); server.closeAllConnections(); server.close(); worker.close(); trace('Probe ended'); }, 300000).unref();
}

main().catch(error => { trace(String(error.stack ?? error)); process.exit(1); });
