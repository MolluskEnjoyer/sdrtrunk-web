# SDRTrunk Web

SDRTrunk with an embedded web interface for controlling P25 trunked radio decoding and RadioReference system lookup — all from your browser. Based on [DSheirer/sdrtrunk](https://github.com/DSheirer/sdrtrunk).

Instead of the desktop Swing/JavaFX GUI, this fork adds a lightweight web frontend that you can access from any device on your network. Ideal for headless setups like a Raspberry Pi 4 running as a dedicated scanner.

## What It Does

- **RadioReference Integration** — Log in with your RR account, search by zip code, browse systems/sites/talkgroups
- **P25 Decoder Control** — Select talkgroups, set a control frequency, start/stop the decoder from the browser
- **Tuner Management** — View connected SDR tuners and their status
- **Activity Log** — Live decoder activity streamed to the web UI
- **Zero Extra Dependencies** — Uses the JDK's built-in HTTP server and the existing Gson library

## Requirements

- **JDK 25** (with JavaFX) — [Bellsoft Liberica Full JDK 25](https://bell-sw.com/pages/downloads/#jdk-25-lts) recommended (the "Full" variant includes JavaFX)
- **SDR Hardware** — RTL-SDR, SDRPlay, Airspy, or other supported tuner
- **RadioReference Account** — Premium account with API access for talkgroup lookups
- **OS** — Linux (x86_64 or aarch64), Windows, or macOS

---

## Quick Start (Any Platform)

```bash
# 1. Clone the repo
git clone https://github.com/MolluskEnjoyer/sdrtrunk-web.git
cd sdrtrunk-web

# 2. Build with Gradle (uses the included wrapper)
./gradlew runtimeZipCurrent

# 3. The built app is in build/image/
#    Find the folder matching your platform, e.g. sdr-trunk-linux-aarch64-v0.6.1/

# 4. Run with the web interface enabled
cd build/image/sdr-trunk-*/bin
./sdr-trunk --web
```

Then open **http://localhost:8080** in your browser.

---

## Step-by-Step: Raspberry Pi 4 (Debian / Raspbian)

### 1. Install Bellsoft Liberica JDK 25 (Full)

The "full" JDK includes JavaFX modules required by SDRTrunk.

```bash
# Add Bellsoft repo
wget -q -O - https://download.bell-sw.com/pki/GPG-KEY-bellsoft | sudo gpg --dearmor -o /usr/share/keyrings/bellsoft.gpg
echo "deb [signed-by=/usr/share/keyrings/bellsoft.gpg] https://apt.bell-sw.com/ stable main" | sudo tee /etc/apt/sources.list.d/bellsoft.list

# Install JDK 25 Full (includes JavaFX)
sudo apt update
sudo apt install -y bellsoft-java25-full

# Verify
java -version
# Expected: openjdk version "25.0.1" or similar
```

If the package isn't available yet for your distro, download the `.deb` manually:
```bash
wget https://download.bell-sw.com/java/25.0.1+11/bellsoft-jdk25.0.1+11-linux-aarch64-full.deb
sudo dpkg -i bellsoft-jdk25.0.1+11-linux-aarch64-full.deb
sudo apt install -f -y
```

### 2. Install USB and Audio Dependencies

```bash
sudo apt install -y libusb-1.0-0-dev libasound2-dev git
```

### 3. Plug In Your SDR

Connect your RTL-SDR (or other tuner) to the Pi's USB port. Verify it's detected:
```bash
lsusb | grep -i rtl
# Should show something like "Realtek Semiconductor Corp. RTL2838"
```

If you're using an RTL-SDR, blacklist the default DVB drivers so SDRTrunk can access it:
```bash
echo "blacklist dvb_usb_rtl28xxu" | sudo tee /etc/modprobe.d/blacklist-rtlsdr.conf
sudo modprobe -r dvb_usb_rtl28xxu
```

### 4. Clone and Build

```bash
cd ~
git clone https://github.com/MolluskEnjoyer/sdrtrunk-web.git
cd sdrtrunk-web

# Build (this will take a while on a Pi 4)
./gradlew runtimeZipCurrent
```

The build output goes to `build/image/sdr-trunk-linux-aarch64-v0.6.1/`.

### 5. Run with Web Interface

```bash
# Headless (no monitor needed)
cd build/image/sdr-trunk-linux-aarch64-v*/bin
./sdr-trunk --web

# Or with a custom port
./sdr-trunk --web --web-port 9090
```

### 6. Open the Web UI

From any device on your network, open:

```
http://<your-pi-ip>:8080
```

Find your Pi's IP with `hostname -I`.

### 7. Using the Web Interface

1. **Login** — Enter your RadioReference username and password
2. **Search** — Type a zip code and click Search to find local systems
3. **Browse** — Click a system to load its talkgroups and sites
4. **Select** — Check the talkgroups you want to monitor (or use "Follow All")
5. **Control Frequency** — The first control channel frequency auto-fills from site data, or enter one manually (in MHz)
6. **Start** — Click "Start Decoder" to begin scanning

---

## Running as a Service (systemd)

To run SDRTrunk Web automatically on boot:

```bash
sudo tee /etc/systemd/system/sdrtrunk-web.service << 'EOF'
[Unit]
Description=SDRTrunk Web Scanner
After=network.target

[Service]
Type=simple
User=pi
WorkingDirectory=/home/pi/sdrtrunk-web/build/image/sdr-trunk-linux-aarch64-v0.6.1
ExecStart=/home/pi/sdrtrunk-web/build/image/sdr-trunk-linux-aarch64-v0.6.1/bin/sdr-trunk --web
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable sdrtrunk-web
sudo systemctl start sdrtrunk-web

# Check status
sudo systemctl status sdrtrunk-web

# View logs
journalctl -u sdrtrunk-web -f
```

---

## Running with GUI + Web

If you have a monitor connected, you can run both the desktop GUI and the web interface simultaneously:

```bash
./sdr-trunk --web
```

This starts the normal SDRTrunk GUI window *and* the web server on port 8080.

---

## CLI Flags

| Flag | Description |
|------|-------------|
| `--web` | Enable the embedded web server |
| `--web-port <port>` | Set the web server port (default: 8080) |

For headless operation, set the `JAVA_TOOL_OPTIONS` environment variable:
```bash
JAVA_TOOL_OPTIONS="-Djava.awt.headless=true" ./sdr-trunk --web
```

---

## Project Structure (Web Integration)

```
src/main/java/io/github/dsheirer/web/
├── WebServer.java                    # Embedded HTTP server (JDK built-in)
└── handler/
    ├── BaseApiHandler.java           # Shared JSON/CORS utilities
    ├── RadioReferenceApiHandler.java  # RR login, zip, systems, talkgroups, sites
    ├── DecoderApiHandler.java        # Start/stop P25 decoder, status polling
    ├── StatusApiHandler.java         # System overview
    ├── TunerApiHandler.java          # List connected SDR tuners
    └── StaticFileHandler.java        # Serves the web UI

src/main/resources/web/
└── index.html                        # Single-page web frontend
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/rr/login` | Login to RadioReference |
| POST | `/api/rr/logout` | Logout |
| GET | `/api/rr/zip?code=12345` | Look up county by zip |
| GET | `/api/rr/systems?county_id=123` | List systems in county |
| GET | `/api/rr/talkgroups?system_id=123` | List talkgroups for system |
| GET | `/api/rr/sites?system_id=123` | List sites and frequencies |
| POST | `/api/decoder/start` | Start P25 decoder |
| POST | `/api/decoder/stop` | Stop decoder |
| GET | `/api/decoder/status` | Decoder state and activity log |
| GET | `/api/tuners` | List SDR tuners |
| GET | `/api/status` | System overview |

---

## Credits

- [DSheirer/sdrtrunk](https://github.com/DSheirer/sdrtrunk) — Original SDRTrunk application
- Web interface integration by MolluskEnjoyer
