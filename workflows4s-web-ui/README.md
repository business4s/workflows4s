# workflows4s-web-ui

## Development

We recommend you have three terminal tabs:

### Terminal 1 - scala JS side

```sh
# in the root project dir
sbt '~workflows4s-web-ui/fastLinkJS'
```

### Terminal 2 - web side
```sh
cd workflows4s-web-ui
npm install
npm run dev
```

### Terminal 3 - scala server side

```sh
# in the root project dir
sbt 'workflows4s-example/runMain workflows4s.example.api.Server'
```

Now navigate to [http://localhost:3000/](http://localhost:5173/) to see your site running.

This setup will rebuild the scala-js bundle upon a file save and vite will automatically reload the page.
