var fs = require('fs');

module.exports = {
    context: __dirname,
    entry: './app.js',
    devServer: {
        https: true,
        headers: {
          'Access-Control-Allow-Origin': '*',
          "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, PATCH, OPTIONS",
          "Access-Control-Allow-Headers": "X-Requested-With, content-type, Authorization"
        },
        proxy: {
            "/r/StreamReddit/*": {
              target: 'http://localhost:3449/',
              secure: true,
              changeOrigin: true,
              pathRewrite: function (path, req){
                  path = path.replace("/r/StreamReddit", "");
                  if (path.includes("/vendor/")) {
                      return path.substring(path.indexOf("/vendor"));
                  }
                  if (path.includes("/js/")) {
                    return path.substring(path.indexOf("/js"));
                  }
                  if (path.includes("/css/")) {
                      return path.substring(path.indexOf("/css"));
                  }
                  console.log([path, ""]);
                  return "";
              }
            },
            "/api/v1/access_token": {
                target: 'https://www.reddit.com',
                secure: true,
                changeOrigin: true,
                pathRewrite: function (path, req) {
                  console.log(path)
                  return path
                }
            },
            "**": {
                target: 'https://oauth.reddit.com',
                secure: true,
                changeOrigin: true,
                pathRewrite: function (path, req) {
                  return path
                }
            }
        }
    }
}
