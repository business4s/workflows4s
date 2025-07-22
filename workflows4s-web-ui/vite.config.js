import {defineConfig} from 'vite'
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";

export default defineConfig({
    base: "/ui/",
    publicDir: false,
    build: {
        outDir: 'dist',
        assetsDir: 'assets',
    },
    plugins: [
        scalaJSPlugin({
            cwd: "..",
            projectID: "workflows4s-web-ui"
        })
    ],
    server: {
        port: 3000,
        proxy: {
            '/api': 'http://localhost:8081',
        }
    }
})