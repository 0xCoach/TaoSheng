import base64
import hashlib
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from subprocess import CompletedProcess
import xml.etree.ElementTree as ET
from device_archive_usb_backup import configuration, create_server, connect_phone


class UsbArchiveBackupTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='taosheng-usb-test-')
        self.root = Path(self.directory.name)
        self.server = create_server(self.root, 0)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        credentials = configuration(self.root)
        self.auth = 'Basic ' + base64.b64encode((credentials['account']+':'+credentials['password']).encode()).decode()

    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.worker.join();self.directory.cleanup()

    def request(self, method, path, body=None, authorized=True, **headers):
        client = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)
        try:
            if authorized:headers['Authorization'] = self.auth
            client.request(method, path, body=body, headers=headers)
            response = client.getresponse()
            return response.status, response.read()
        finally:client.close()

    def test_authentication_and_directory_escape_are_rejected(self):
        self.assertEqual(401, self.request('PROPFIND','/Resound-Backup/',authorized=False)[0])
        for path in ('/../usb-backup-credentials.json','/Resound-Backup/%2e%2e/usb-backup-credentials.json','/Resound-Backup/objects/secret.bin'):
            self.assertEqual(403, self.request('GET',path)[0])
        self.assertEqual('127.0.0.1', self.server.server_address[0])
        self.assertEqual(404,self.request('PROPFIND','/Resound-Backup/history/',Depth='1')[0])
        self.assertEqual(404,self.request('GET','/Resound-Backup/resound-state.zip')[0])

    def test_atomic_upload_readback_listing_and_retained_backup(self):
        self.assertEqual(201, self.request('MKCOL','/Resound-Backup/')[0])
        self.assertEqual(201, self.request('MKCOL','/Resound-Backup/device-archives/')[0])
        audio = b'test audio bytes\0' * 1000
        name = hashlib.sha256(audio).hexdigest()+'.bin'
        target = '/Resound-Backup/device-archives/'+name
        temporary = target+'.11111111-1111-1111-1111-111111111111.part'
        self.assertEqual(201, self.request('PUT',temporary,audio)[0])
        self.assertEqual(201, self.request('MOVE',temporary,Destination=target)[0])
        self.assertEqual((200,audio), self.request('GET',target))
        status, listing = self.request('PROPFIND','/Resound-Backup/device-archives/',Depth='1')
        self.assertEqual(207,status)
        hrefs = [node.text for node in ET.fromstring(listing).iter('{DAV:}href')]
        self.assertIn(target,hrefs)
        self.assertEqual(403,self.request('DELETE',target)[0])
        self.assertEqual((200,audio),self.request('GET',target))

    def test_reconnect_probes_listener_before_mapping_and_targets_only_authorized_package(self):
        with patch('device_archive_usb_backup.subprocess.run') as command:
            command.side_effect=[CompletedProcess([],0,stdout='0\n'),CompletedProcess([],0,stdout=''),
                CompletedProcess([],0,stdout='Result: Bundle[{protocol=1, queued=true, enabled=true}]')]
            self.assertTrue(connect_phone(self.root,self.server.server_port,Path('adb'),'TEST-USB','app.resound.validation'))
            self.assertEqual(3,command.call_count)
            calls=[c.args[0] for c in command.call_args_list]
            self.assertEqual(['adb','-s','TEST-USB','reverse',f'tcp:{self.server.server_port}',f'tcp:{self.server.server_port}'],calls[1])
            self.assertIn('content://app.resound.validation.device-archive-backup',calls[2])
            self.assertEqual('0',calls[2][calls[2].index('--user')+1])
            self.assertNotIn('force-stop',calls[2])
            command.reset_mock()
            # Wrong credentials cannot trigger even a mapping, let alone maintenance.
            path=self.root/'usb-backup-credentials.json';value=configuration(self.root)
            value['password']='different-password';path.write_text(json.dumps(value),encoding='utf-8')
            with self.assertRaises(ConnectionError):
                connect_phone(self.root,self.server.server_port,Path('adb'),'TEST-USB','app.resound.validation')
            command.assert_not_called()


if __name__ == '__main__':unittest.main()
