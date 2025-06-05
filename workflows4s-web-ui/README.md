# workflows4s-web-ui

## Setup instructions

To run the program in a browser you will need to have npm (or yarn) installed.

Before your first run and for your tests to work, **you must** install the node dependencies with:

```sh
npm install
```

This example uses Vite as our bundler and dev server, which is a modern and fast build tool. There are other options you might prefer like Webpack, Parcel.js, or even just vanilla JavaScript.

We recommend you have two terminal tabs open in the directory containing this README file.

In the first, we'll run sbt.

```sh
sbt
```

From now on, we can recompile the app with `fastLinkJS` or `fullLinkJS` _**but please note** that the `tyrianapp.js` file in the root is expecting the output from `fastLinkJS`_.

Run `fastLinkJS` now to get an initial build in place.

Then start your dev server, with:

```sh
npm run dev
```

Now navigate to [http://localhost:5173/](http://localhost:5173/) to see your site running.

If you leave Vite's dev server running, all you have to do is another `fastLinkJS` or `fullLinkJS` and your app running in the browser should hot-reload the new code.
