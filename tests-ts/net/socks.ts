/**
 * socks.js — cliente HTTP(S) via SOCKS5 (stdlib Node: net/tls, sem deps).
 * Suporta CONNECT + handshake SOCKS5 + TLS com SNI (https_proxy equivalente
 * a socks5h://). Exporta TUNNEL_UP (probe rápida no import).
 */
import net from 'node:net';
import tls from 'node:tls';

export const TUNNEL_HOST = '127.0.0.1';
export const TUNNEL_PORT = 19053;

async function probeTunnel(): Promise<boolean> {
  return new Promise((resolve) => {
    const s = net.connect(TUNNEL_PORT, TUNNEL_HOST);
    const done = (v: boolean) => { try { s.destroy(); } catch { /* noop */ } resolve(v); };
    s.setTimeout(4000);
    s.on('connect', () => {
      s.write(Buffer.from([0x05, 0x01, 0x00]));
      s.once('data', (d: Buffer) => done(d.length >= 2 && d[0] === 0x05 && d[1] === 0x00));
    });
    s.on('timeout', () => done(false));
    s.on('error', () => done(false));
  });
}

export const TUNNEL_UP: boolean = await probeTunnel();

export const FETCH_TIMEOUT_MS = Number(process.env.RC_SOCKS_TIMEOUT_MS ?? 45000);

function withTimeout<T>(p: Promise<T>, ms: number, what: string): Promise<T> {
  let t: NodeJS.Timeout | undefined;
  const to = new Promise<never>((_, rej) => { t = setTimeout(() => rej(new Error(`${what} timeout ${ms}ms`)), ms); });
  return Promise.race([p, to]).finally(() => clearTimeout(t));
}

function readExactly(sock: net.Socket | tls.TLSSocket, n: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    let acc = Buffer.alloc(0);
    const onData = (d: Buffer) => {
      acc = Buffer.concat([acc, d]);
      if (acc.length >= n) { cleanup(); resolve(acc.subarray(0, n)); }
    };
    const onErr = (e: Error) => { cleanup(); reject(e); };
    const cleanup = () => { sock.off('data', onData); sock.off('error', onErr); };
    sock.on('data', onData);
    sock.on('error', onErr);
  });
}

async function socksConnect(host: string, port: number, sniHost: string, useTls: boolean): Promise<net.Socket | tls.TLSSocket> {
  const sock: net.Socket = net.connect(TUNNEL_PORT, TUNNEL_HOST);
  await new Promise<void>((resolve, reject) => {
    sock.setTimeout(15000);
    sock.on('connect', () => resolve());
    sock.on('timeout', () => reject(new Error('socks dial timeout')));
    sock.on('error', (e) => reject(e));
  });
  sock.setTimeout(0);
  sock.write(Buffer.from([0x05, 0x01, 0x00]));
  const hs = await readExactly(sock, 2);
  if (hs[0] !== 0x05 || hs[1] !== 0x00) throw new Error(`socks handshake: ${hs.toString('hex')}`);
  const hb = Buffer.from(host, 'utf8');
  const req = Buffer.concat([
    Buffer.from([0x05, 0x01, 0x00, 0x03, hb.length]), hb,
    Buffer.from([(port >> 8) & 0xff, port & 0xff]),
  ]);
  sock.write(req);
  const rp = await readExactly(sock, 10);
  if (rp[0] !== 0x05 || rp[1] !== 0x00) throw new Error(`socks connect: ${rp.toString('hex')}`);
  if (!useTls) return sock;
  const tlsSock = tls.connect({ socket: sock, servername: sniHost, rejectUnauthorized: true });
  await new Promise<void>((resolve, reject) => {
    tlsSock.on('secureConnect', () => resolve());
    tlsSock.on('error', (e) => reject(e));
  });
  return tlsSock;
}

export type SimpleHeaders = Map<string, string>;

export interface SimpleResp {
  status: number;
  headers: SimpleHeaders;
  body: string;
}

export class SocksHttp {
  constructor(private h: string = TUNNEL_HOST, private p: number = TUNNEL_PORT) {}

  async get(url: string, headers: Record<string, string> = {}): Promise<SimpleResp> {
    return withTimeout(this.getInner(url, headers), FETCH_TIMEOUT_MS, `GET ${url.slice(0, 80)}`);
  }

  private async getInner(url: string, headers: Record<string, string> = {}): Promise<SimpleResp> {
    const u = new URL(url);
    const useTls = u.protocol === 'https:';
    const port = u.port ? Number(u.port) : useTls ? 443 : 80;
    const sock = await socksConnect(u.hostname, port, u.hostname, useTls);
    const path = u.pathname + (u.search || '');
    const lines = [`GET ${path} HTTP/1.1`, `Host: ${u.hostname}`, 'Connection: close'];
    for (const [k, v] of Object.entries(headers)) lines.push(`${k}: ${v}`);
    const raw = await new Promise<Buffer>((resolve, reject) => {
      const acc: Buffer[] = [];
      sock.on('data', (d: Buffer) => acc.push(d));
      sock.on('end', () => { try { sock.destroy(); } catch { /* noop */ } resolve(Buffer.concat(acc)); });
      sock.on('error', (e: Error) => reject(e));
      sock.write(lines.join('\r\n') + '\r\n\r\n');
    });
    const idx = raw.indexOf('\r\n\r\n');
    const head = raw.subarray(0, idx).toString('latin1');
    const hlines = head.split('\r\n');
    const status = Number(hlines[0].split(' ')[1] ?? 0);
    const hm: SimpleHeaders = new Map();
    for (const l of hlines.slice(1)) {
      const i = l.indexOf(':');
      if (i > 0) hm.set(l.slice(0, i).trim().toLowerCase(), l.slice(i + 1).trim());
    }
    let body = raw.subarray(idx + 4);
    // de-chunk simples
    if ((hm.get('transfer-encoding') ?? '').includes('chunked')) {
      const out: Buffer[] = [];
      let p = 0;
      const s = body.toString('latin1');
      while (p < s.length) {
        const eol = s.indexOf('\r\n', p);
        if (eol < 0) break;
        const size = parseInt(s.slice(p, eol).split(';')[0].trim(), 16);
        if (!size) break;
        const start = eol + 2;
        out.push(body.subarray(start, start + size));
        p = start + size + 2;
      }
      body = Buffer.concat(out);
    }
    return { status, headers: hm, body: body.toString('utf8') };
  }
}
