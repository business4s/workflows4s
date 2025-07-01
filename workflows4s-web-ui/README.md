# workflows4s-web-ui

## Development

We recommend you have two terminal tabs:

### Terminal 1 - scala side

```sh
# in the root project dir
sbt 
> ~workflows4s-web-ui/fastLinkJS
```

### Terminal 2 - web side
```sh
cd workflows4s-web-ui
npm install
```

Now navigate to [http://localhost:3000/](http://localhost:5173/) to see your site running.

This setup will rebuild the scala-js bundle upon a file save and vite will automatically reload the page.
