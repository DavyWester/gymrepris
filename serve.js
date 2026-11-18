// Serves this folder on the local network for trying the app: node serve.js [port]
const http = require('http'), fs = require('fs'), path = require('path'), os = require('os');
let port = +process.argv[2] || 8123;
const lastPort = port + 20;
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json' };

const server = http.createServer((req, res) => {
  const name = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html';
  const file = path.join(__dirname, name);
  if (!file.startsWith(__dirname)) { res.writeHead(403).end(); return; }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404).end('Not found'); return; }
    res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' }).end(data);
  });
});
// Windows reserves some ports and others are taken; the next free one is used.
server.on('error', err => {
  if ((err.code === 'EACCES' || err.code === 'EADDRINUSE') && port < lastPort) server.listen(++port);
  else { console.error('Kunde inte starta: ' + err.message); process.exit(1); }
});
server.on('listening', () => {
  console.log('http://localhost:' + port);
  for (const list of Object.values(os.networkInterfaces()))
    for (const a of list) if (a.family === 'IPv4' && !a.internal) console.log('http://' + a.address + ':' + port);
});
server.listen(port);
