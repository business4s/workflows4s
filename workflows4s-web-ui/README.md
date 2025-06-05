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

## Supported Effect Types

From version `0.6.0`, Tyrian supports both Cats Effect 3 and ZIO 2.0. This template defaults to CE3 and IO (as this is the author's habit), but there is an example of a [ZIO tyrian project](https://github.com/PurpleKingdomGames/tyrian/blob/main/examples) available, and conversion is fairly straightforward.

The [build](https://github.com/PurpleKingdomGames/tyrian/blob/main/examples/build.sbt#L153) for the ZIO example has libraries that you need to add/replace. You need to set up the right [imports](https://github.com/PurpleKingdomGames/tyrian/blob/main/examples/zio/src/main/scala/example/Main.scala#L6) and replace `IO` with [`Task`](https://github.com/PurpleKingdomGames/tyrian/blob/main/examples/zio/src/main/scala/example/Main.scala#L13).

Otherwise, it's identical.

## Supported Build Tools

Tyrian works equally well with sbt or Mill. Most of the examples are given in sbt, and this g8 template uses sbt too. However there is a [Mill example](https://github.com/PurpleKingdomGames/tyrian/tree/main/examples) project that serves as a good starting point.