const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const configDir = process.argv[2];
  const read = name => path.join(configDir, name);
  const config = JSON.parse(fs.readFileSync(read('config.json'), 'utf8'));
  process.env.MEDIASOUP_WORKER_BIN = config.workerPath;
  process.env.LD_LIBRARY_PATH = path.dirname(config.workerPath);
  let close;
  try {
    const relayModule = path.join(__dirname, 'dist', 'src', 'phone-relay.js');
    const { startPhoneRelay } = await import(pathToFileURL(relayModule).href);
    close = await startPhoneRelay(config.ip, config.hostKey);
    if (fs.existsSync(read('stop'))) return;
    fs.writeFileSync(read('ready.tmp'), '');
    fs.renameSync(read('ready.tmp'), read('ready'));

    await new Promise(resolve => {
      const stopWatch = setInterval(() => {
        if (!fs.existsSync(read('stop'))) return;
        clearInterval(stopWatch);
        resolve();
      }, 250);
      stopWatch.unref();
    });
  } catch (error) {
    fs.writeFileSync(read('failed'), error instanceof Error ? error.name : 'Error');
    process.exitCode = 1;
  } finally {
    await close?.();
  }
}

main().catch(() => { process.exitCode = 1; });
