# Private cross-network Symphonia demo

Use an Ubuntu VM with a public IPv4 address and two physical Android phones.
The VM runs the existing signaling service and mediasoup worker. Both phones
reach it over WireGuard, and the Listener joins by room PIN. The GitHub
repository supplies the source. This setup uses no GitHub Actions or Codespaces
compute.

## Prepare the VM

1. Use a fresh VM with an SSH key and restrict SSH to the operator's IP.
   At the provider firewall, allow inbound UDP 51820 for WireGuard. Keep TCP
   8080 and TCP/UDP 44444 closed on the public interface.
2. Install WireGuard, nftables, Git, and Node.js 22 or newer with npm. The
   service unit expects Node at `/usr/bin/node`. If mediasoup needs to build its
   worker locally, install `build-essential` and `python3-pip` as well.
3. Put the demo source revision at `/opt/symphonia`. Use the same revision for
   the server and both APKs. For workspace changes that haven't been published,
   transfer the source over SSH to the VM. Exclude local credentials, build
   output, and dependency directories.
4. Build as an ordinary user with write access to the checkout, then make the
   checkout readable by the service account.

   ```sh
   cd /opt/symphonia/signaling
   npm ci
   npm run lint
   npm test
   npm run build
   ```

## Connect the phones

1. Generate a server key with `wg genkey` in a root-only directory with
   `umask 077`, then derive its public key with `wg pubkey`. In WireGuard on
   each phone, create a tunnel from scratch so its private key stays on the
   device. Exchange only public keys.
2. On the VM, copy `wg0.conf.example` to `/etc/wireguard/wg0.conf` with
   ownership root and mode 0600. Fill in the server private key and the two
   phone public keys. Keep the completed tunnel configuration outside Git.
3. Use these settings in each phone's WireGuard tunnel:

   | Setting | Speaker | Listener |
   | --- | --- | --- |
   | Interface address | `10.77.0.2/32` | `10.77.0.3/32` |
   | Peer public key | VM public key | VM public key |
   | Peer endpoint | VM public IPv4 address, port 51820 | Same |
   | Peer allowed IPs | `10.77.0.1/32` | `10.77.0.1/32` |
   | Persistent keepalive | 25 seconds | 25 seconds |

4. Apply the demo firewall and start WireGuard before starting the app
   service. Run these commands from `/opt/symphonia/infra/demo`.

   ```sh
   sudo nft -c -f symphonia-demo.nft
   sudo nft -f symphonia-demo.nft
   sudo systemctl start wg-quick@wg0.service
   sudo useradd --system --user-group --no-create-home --shell /usr/sbin/nologin symphonia
   sudo install -m 644 symphonia-demo.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl start symphonia-demo.service
   sudo systemctl status symphonia-demo.service
   ```

   The demo firewall drops access to the app ports from all other interfaces
   and sources. If the VM has a separate default-deny firewall, allow the VPN
   handshake there and allow the two VPN peers to reach the app ports on
   `wg0`. The commands start a temporary session. Apply the firewall again
   before restarting the service after a reboot.

## Run and verify

1. Enable the tunnel on both phones. Check `sudo wg show` for both peer
   handshakes and open `http://10.77.0.1:8080/healthz` on each phone. From
   outside the VPN, confirm the VM's public IP cannot serve that health page
   and the provider firewall blocks both media protocols.
2. From `android/`, run `./gradlew :gate2-harness:assembleDebug` (use
   `gradlew.bat` on Windows). Omit the `gate2RelayProbeBundle` property so the
   Speaker exposes the external signaling field. Install the same APK on both
   devices and enter `ws://10.77.0.1:8080/gate2` on both. The debug APK permits
   this exact address for signaling inside WireGuard. WebRTC media uses
   DTLS-SRTP.
3. Keep the Speaker on Wi-Fi. Disable Wi-Fi on the Listener and use mobile
   data. On the Speaker, select a known-good media app, grant capture consent,
   and start sharing. Copy the six-character PIN and enter it on the Listener.
   Confirm audible playback and increasing inbound audio bytes. Confirm an
   invalid PIN fails and **Stop Sharing** ends playback.
4. Record the source revision, device models, network types, and receiver
   stats. If the PIN works but playback is silent, check the selected ICE
   candidate and SFU logs, then check `sudo ss -lntup` on the VM for both media
   sockets on `10.77.0.1:44444`. Keep audio out of logs.
5. Stop the demo with `sudo systemctl stop symphonia-demo.service wg-quick@wg0.service`.
   Delete the tunnel profiles from the phones and remove the VM's peer keys
   from its WireGuard configuration before reuse. To remove the temporary
   firewall rules after shutdown, run `sudo nft delete table inet symphonia_demo`.

This checks live audio across two networks over a private VPN. Public room
joining and the formal STUN/TURN latency trial are separate.

## Browser-only Listener

The cloud listener entry point keeps Speaker signaling on WireGuard at
`10.77.0.1:8080`. A browser joins through a ten-minute signed invite. Do
not expose the original prototype entry point or its diagnostic routes.

1. Allow public TCP 80 and 443 and TCP/UDP 44445 in the provider firewall.
   Keep the private signaling and media rules above. The extra media
   sockets bind the EC2 private interface and advertise the EC2 public
   IPv4 address. Keep that public address stable for the demo.
2. Configure nginx using `cloud-listener.nginx.conf.example`, substituting
   the public address. Enable only the HTTP block for initial certificate
   issuance and serve `/var/www/symphonia-acme` for the ACME challenge.
   Use Certbot 5.4 or later with `certonly --preferred-profile shortlived
   --webroot -w /var/www/symphonia-acme --ip-address PUBLIC_IPV4`. Enable
   the HTTPS block after issuance and run `nginx -t` before reloading.
   Configure automatic renewal and reload nginx after renewal.
3. In a root-readable systemd environment file, set `CLOUD_PUBLIC_IPV4`,
   `CLOUD_PRIVATE_IPV4` (the EC2 primary interface address), and
   `CLOUD_INVITE_KEY` (32 random bytes encoded as hex). Add that file via
   `EnvironmentFile=` in a unit override, clear the original `ExecStart`,
   and set `ExecStart=/usr/bin/node dist/src/cloud-listener.js`. Build and
   restart the service. The Speaker must start a fresh room after restart.
4. Once the new room is producing audio, mint its invite from SSH by
   requesting `http://127.0.0.1:8081/invite` with `roomId` and `hostKey`
   (the value of `CLOUD_INVITE_KEY`) as query parameters. Keep the key
   and invite out of logs. The public nginx configuration does not route
   the issuer. Send only the resulting HTTPS invite to the Listener.
5. Confirm public `/invite` and `/debug/rooms` return 404, and a browser
   WebSocket without a valid invite is rejected. Then open the signed
   link on a device outside the VPN, tap Play, and verify audible playback.
