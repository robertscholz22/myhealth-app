// Prints the app's connection code for a relay: `myhealth-ai:1:` + base64url({"u":url,"k":key}),
// the format of Kotlin `AiConnectionCode`. Usage: node scripts/connection-code.mjs <relay-url> <registration-key>
export function connectionCode(url, key) {
  const json = JSON.stringify({ u: url.replace(/\/+$/, ''), k: key });
  return 'myhealth-ai:1:' + Buffer.from(json, 'utf8').toString('base64url');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [url, key] = process.argv.slice(2);
  if (!url || !key) {
    console.error('Usage: node scripts/connection-code.mjs <relay-url> <registration-key>');
    process.exit(1);
  }
  console.log(connectionCode(url, key));
}
