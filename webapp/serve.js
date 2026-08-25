#!/usr/bin/env node
/*
 * One origin in front of the two processes the port runs: the Akka service, which answers
 * the data, and the vendored Next.js server, which renders the interface.
 *
 * This exists rather than a Next rewrite because a rewrite buffers the response. The page
 * received the event stream's first frame and nothing after it, which looks exactly like a
 * server that stopped sending -- and the port's own SSE integration tests passed throughout,
 * because they never went through Next. Everything here is piped, so a frame reaches the
 * browser when it is written.
 *
 *   node serve.js [listen] [apiPort] [webPort]
 */

const http = require('http');

const LISTEN = Number(process.argv[2] || process.env.PORT || 3300);
const API = Number(process.argv[3] || process.env.TEABLE_API_PORT || 9082);
const WEB = Number(process.argv[4] || process.env.TEABLE_WEB_PORT || 3200);

const isData = (url) => url.startsWith('/api/') || url.startsWith('/socket');

http
  .createServer((req, res) => {
    const target = isData(req.url) ? API : WEB;
    const upstream = http.request(
      {
        host: '127.0.0.1',
        port: target,
        method: req.method,
        path: req.url,
        headers: { ...req.headers, host: `127.0.0.1:${target}` },
      },
      (response) => {
        res.writeHead(response.statusCode, response.headers);
        response.pipe(res);
      }
    );
    upstream.on('error', (error) => {
      res.writeHead(502, { 'content-type': 'text/plain' });
      res.end(`upstream ${target}: ${error.message}`);
    });
    req.pipe(upstream);
  })
  .listen(LISTEN, '127.0.0.1', () => {
    console.log(`serving ${LISTEN}: /api -> ${API}, everything else -> ${WEB}`);
  });
