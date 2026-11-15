"""USB-only WebDAV adapter for full-source archives. Python stdlib; bind loopback only.

Phone uses adb reverse tcp:18743 tcp:18743 and WebDAV http://127.0.0.1:18743/.
Credentials stay in the explicit backup directory; never print them in logs.
Completed archives cannot be deleted through this server. No LAN/public listener.
"""
import argparse
import base64
import email.utils
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlsplit, quote
from xml.sax.saxutils import escape


def configuration(root):
    root.mkdir(parents=True, exist_ok=True)
    path = root / 'usb-backup-credentials.json'
    if not path.exists():
        value = {'account': 'taosheng-usb', 'password': secrets.token_urlsafe(32)}
        # Exclusive creation preserves credentials if two helpers start together.
        try:
            with path.open('x', encoding='utf-8') as out:
                json.dump(value, out);out.flush();os.fsync(out.fileno())
        except FileExistsError:
            pass
    value = json.loads(path.read_text(encoding='utf-8'))
    assert value['account'] == 'taosheng-usb' and len(value['password']) >= 32
    return value


def create_server(root, port):
    root = root.resolve()
    credentials = configuration(root)
    data = root / 'data'
    data.mkdir(exist_ok=True)
    expected = 'Basic ' + base64.b64encode((credentials['account'] + ':' + credentials['password']).encode()).decode()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *_):
            pass

        def reply(self, code, body=b'', **headers):
            self.send_response(code)
            self.send_header('Content-Length', str(len(body)))
            for key, value in headers.items():
                self.send_header(key.replace('_', '-'), value)
            self.end_headers()
            if self.command != 'HEAD' and body:
                self.wfile.write(body)

        def authorized(self):
            if not hmac.compare_digest(self.headers.get('Authorization', ''), expected):
                self.close_connection = True
                self.reply(401, WWW_Authenticate='Basic realm="TaoSheng USB backup"')
                return False
            return True

        def path_for(self, value):
            parsed = urlsplit(value)
            if parsed.query or parsed.fragment:
                raise ValueError('invalid path')
            if parsed.netloc and (parsed.hostname != '127.0.0.1' or parsed.port != self.server.server_port):
                raise ValueError('foreign destination')
            parts = unquote(parsed.path).strip('/').split('/')
            if any(p in ('', '.', '..') or '\\' in p or any(ord(c) < 32 for c in p) for p in parts):
                raise ValueError('invalid path')
            relative = '/'.join(parts)
            allowed = relative in ('Resound-Backup', 'Resound-Backup/device-archives') or re.fullmatch(
                r'Resound-Backup/(device-archives/[a-f0-9]{64}\.(bin|json)(\.[a-f0-9-]{36}\.part)?|\.probe-[a-f0-9-]{36}\.part)', relative)
            if not allowed:
                raise ValueError('archive namespace only')
            result = (data / relative).resolve()
            if not result.is_relative_to(data.resolve()):
                raise ValueError('outside backup directory')
            return result

        def target(self):
            if not self.authorized():
                return None
            # The shared settings page also probes ordinary history/legacy metadata.
            # These are absent on this archive-only service, rather than forbidden credentials.
            parsed = urlsplit(self.path)
            absent = {'Resound-Backup/history', 'Resound-Backup/objects', 'Resound-Backup/resound-state.zip',
                      'Resound-Backup/resound-state.zip.enc', '回响备份'}
            if self.command in ('GET', 'HEAD', 'PROPFIND') and not parsed.query and not parsed.fragment and unquote(parsed.path).strip('/') in absent:
                self.reply(404);return None
            try:
                return self.path_for(self.path)
            except ValueError:
                self.close_connection = True;self.reply(403)
                return None

        def do_MKCOL(self):
            path = self.target()
            if path is None:return
            if path.exists():self.reply(405);return
            if not path.parent.is_dir():self.reply(409);return
            path.mkdir();self.reply(201)

        def do_PROPFIND(self):
            path = self.target()
            if path is None:return
            if not path.exists():self.reply(404);return
            if self.headers.get('Depth', '1') not in ('0', '1'):self.reply(403);return
            # Existing client sends an empty PROPFIND body. Consume bounded input regardless.
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 <= size <= 65536:self.close_connection=True;self.reply(413);return
            self.rfile.read(size)
            entries = [path] + (sorted(path.iterdir()) if path.is_dir() and self.headers.get('Depth', '1') == '1' else [])
            responses = []
            for entry in entries:
                if entry.is_symlink() or not entry.resolve().is_relative_to(data.resolve()):continue
                href = '/' + quote(entry.relative_to(data).as_posix()) + ('/' if entry.is_dir() else '')
                stat = entry.stat()
                resource = '<d:collection/>' if entry.is_dir() else ''
                responses.append(f'<d:response><d:href>{escape(href)}</d:href><d:propstat><d:prop>'
                    f'<d:resourcetype>{resource}</d:resourcetype><d:getcontentlength>{stat.st_size if entry.is_file() else 0}</d:getcontentlength>'
                    f'<d:getlastmodified>{email.utils.formatdate(stat.st_mtime, usegmt=True)}</d:getlastmodified>'
                    '</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>')
            body = ('<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">' + ''.join(responses) + '</d:multistatus>').encode()
            self.reply(207, body, Content_Type='application/xml; charset=utf-8')

        def do_PUT(self):
            path = self.target()
            if path is None:return
            if not path.name.endswith('.part') or not path.parent.is_dir():self.close_connection=True;self.reply(403);return
            try:size = int(self.headers.get('Content-Length', '-1'))
            except ValueError:size = -1
            if not 0 <= size <= 2 * 1024**3:self.close_connection=True;self.reply(413);return
            remaining = size
            try:
                with path.open('xb') as out:
                    while remaining:
                        chunk = self.rfile.read(min(remaining, 1024*1024))
                        if not chunk:raise ConnectionError('incomplete upload')
                        out.write(chunk);remaining -= len(chunk)
                    out.flush();os.fsync(out.fileno())
                self.reply(201)
            except FileExistsError:self.close_connection=True;self.reply(409)
            except (ConnectionError, OSError):
                path.unlink(missing_ok=True);self.close_connection=True;self.reply(507)

        def do_MOVE(self):
            source = self.target()
            if source is None:return
            try:destination = self.path_for(self.headers.get('Destination', ''))
            except ValueError:self.reply(403);return
            if not source.is_file():self.reply(404);return
            if not source.name.endswith('.part') or destination.suffix not in ('.bin', '.json') or source.parent != destination.parent or not source.name.startswith(destination.name + '.'):
                self.reply(403);return
            if destination.exists() and self.headers.get('Overwrite', 'T') != 'T':self.reply(412);return
            if destination.suffix == '.bin':
                with source.open('rb') as stream:
                    actual = hashlib.file_digest(stream, 'sha256').hexdigest()
                if actual != destination.stem:self.reply(422);return
            else:
                try:
                    manifest = json.loads(source.read_text(encoding='utf-8'))
                    if manifest.get('format') != 1 or manifest['record']['id'] != destination.stem:raise ValueError('manifest identity')
                except (ValueError, KeyError):self.reply(422);return
            os.replace(source, destination);self.reply(201)

        def do_DELETE(self):
            path = self.target()
            if path is None:return
            if not path.name.endswith('.part'):self.reply(403);return
            if not path.is_file():self.reply(404);return
            path.unlink();self.reply(204)

        def do_GET(self):
            path = self.target()
            if path is None:return
            if not path.is_file():self.reply(404);return
            self.send_response(200);self.send_header('Content-Length', str(path.stat().st_size));self.end_headers()
            if self.command == 'HEAD':return
            with path.open('rb') as source:
                while chunk := source.read(1024*1024):self.wfile.write(chunk)

        do_HEAD = do_GET

    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.daemon_threads = True
    return server


def connect_phone(root, port, adb, serial, package):
    """One explicit USB reconnect, after checking this backup listener; no monitor."""
    if package not in ('app.resound', 'app.resound.validation'):
        raise ValueError('unsupported package')
    if not re.fullmatch(r'[A-Za-z0-9._-]{1,128}', serial) or not 1 <= port <= 65535:
        raise ValueError('explicit USB serial and valid port required')
    credentials = json.loads((root / 'usb-backup-credentials.json').read_text(encoding='utf-8'))
    auth = base64.b64encode((credentials['account']+':'+credentials['password']).encode()).decode()
    probe = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
    try:
        probe.request('HEAD','/Resound-Backup/',headers={'Authorization':'Basic '+auth})
        response = probe.getresponse();response.read()
        if response.status not in (200,404):raise ConnectionError('backup listener authentication failed')
    finally:probe.close()
    prefix = [str(adb),'-s',serial]
    user = subprocess.run(prefix+['shell','am','get-current-user'],check=True,capture_output=True,text=True,timeout=15).stdout.strip()
    if not re.fullmatch(r'\d+',user):raise ConnectionError('current Android user unavailable')
    subprocess.run(prefix+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,capture_output=True,text=True,timeout=15)
    reply = subprocess.run(prefix+['shell','content','call','--user',user,
        '--uri','content://'+package+'.device-archive-backup','--method','backup-ready'],
        check=True,capture_output=True,text=True,timeout=15)
    protocol = re.search(r'\bprotocol=1\b',reply.stdout)
    enabled = re.search(r'\benabled=(true|false)\b',reply.stdout)
    queued = re.search(r'\bqueued=(true|false)\b',reply.stdout)
    if not protocol or not enabled or not queued or enabled.group(1) != queued.group(1):
        raise ConnectionError('phone backup-ready IPC unavailable; feature build required')
    return queued.group(1) == 'true'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path, help='Independent computer backup directory')
    parser.add_argument('--port', type=int, default=18743)
    parser.add_argument('--init', action='store_true', help='Create credentials without starting server')
    parser.add_argument('--connect-phone', action='store_true', help='Check running backup server, restore USB reverse and notify phone once')
    parser.add_argument('--adb', type=Path, help='ADB executable; required with --connect-phone')
    parser.add_argument('--serial', help='Explicit USB debugging device serial; required with --connect-phone')
    parser.add_argument('--package', choices=('app.resound','app.resound.validation'), default='app.resound')
    args = parser.parse_args()
    if args.connect_phone:
        if args.init or args.adb is None or not args.serial:parser.error('--connect-phone requires --adb and --serial, without --init')
        try:queued=connect_phone(args.root.resolve(),args.port,args.adb,args.serial,args.package)
        except Exception as error:
            parser.exit(1,'USB connection was not completed: '+type(error).__name__+'\n')
        print('USB mapping restored; '+('maintenance opportunity accepted.' if queued else 'automatic maintenance remains disabled.')+
              ' Archive and deletion checks remain required.')
    elif args.init:
        configuration(args.root.resolve());print('USB backup credentials initialized; no listener started.')
    else:
        server = create_server(args.root, args.port)
        print(f'USB archive backup listening only on 127.0.0.1:{server.server_port}', flush=True)
        try:server.serve_forever()
        except KeyboardInterrupt:pass
        finally:server.server_close()
