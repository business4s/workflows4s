
The following has to be run manually to keep website (bpmns presented there) in sync.
This is likely to be replaced by more jvm-friendly solution and be incorporated into tests.

```bash
npx puppeteer browsers install
npm install
# for troubleshooting
export DEBUG=puppeteer:browsers:launcher
node ./render-docs.mjs
```