# genomon-api

`genomon-api` is an API server for Genomon pipelines.
`genomon-api` supports the following operations over HTTP:
- running DNA/RNA pipelines
- tracking analysis states
- managing job histories
- serving result files

## Design overview

`genomon-api` launches `genomon_pipeline_cloud` containers through Docker remote
API. Samples config `.csv` and parameters config `.cfg` are automatically
generated from HTTP requests and then mounted to the containers.

## Prerequisites

- Leiningen >= 2.9.1
- Java >= 11
- Docker >= 19.03

## Developing

### Setup

When you first clone this repository, run:

```sh
lein duct setup
```

This will create files for local configuration, and prep your system
for the project.

To launch the system, you need to supply configs for AWS.
`docker-compose` will read values from environment variables and `.env` file.

```sh
cp .env.example .env
vim .env
docker-compose up --build
# access http://localhost:8000 to open Swagger UI
```

You need to export `.env` file to access these values from `lein repl`.

```sh
eval "$(cat .env | sed -e 's/^/export /g')"
lein repl
```

You can use a local config file (`dev/resources/local.edn`) for changing
configs without restarting JVM. For example:

```clojure
{:genomon-api.aws/profile-credentials-provider
 {:profile-name "..."},

 :genomon-api.aws/client
 {:credentials-provider #ig/ref :genomon-api.aws/profile-credentials-provider},

 :genomon-api.executor.genomon-pipeline-cloud.config/instance-option
 {:aws-subnet-id "...",
  :aws-security-group-id "...",
  :aws-key-name "...",
  :aws-ecs-instance-role-name "..."}

 :genomon-api.executor.genomon-pipeline-cloud/executor
 {:env {"AWS_ACCESS_KEY_ID" "...",
        "AWS_SECRET_ACCESS_KEY" "...",
        "AWS_DEFAULT_REGION" "..."},
  :output-bucket "s3://..."}}
```

For details of required privileges, please refer to the setup documents of
[ecsub](https://github.com/aokad/ecsub#3-setup) and
[genomon_pipeline_cloud](https://github.com/Genomon-Project/genomon_pipeline_cloud/wiki/How-to-use-(v0.3)#setup).

### Environment

To begin developing, start with a REPL.

```sh
lein repl
```

Then load the development environment.

```clojure
user=> (dev)
:loaded
```

Run `go` to prep and initiate the system.

```clojure
dev=> (go)
:duct.server.http.jetty/starting-server {:port 3000}
:initiated
```

By default this creates a web server at <http://localhost:3000>.

When you make changes to your source files, use `reset` to reload any
modified files and reset the server.

```clojure
dev=> (reset)
:reloading (...)
:resumed
```

### Testing

Testing is fastest through the REPL, as you avoid environment startup
time.

```clojure
dev=> (test)
...
```

But you can also run tests through Leiningen.

```sh
lein eftest
```

### Build

#### Jar
To build a standalone jar, run:

```sh
lein uberjar
```

#### Docker
To build a docker image, run:

```sh
docker build . -t genomon/genomon-api:latest
```

## License
Copyright 2020 [Xcoo, Inc.](https://xcoo.jp)
Licensed under the [GNU Genral Public License, Version 3.0](https://github.com/chrovis/genomon-api/blob/master/LICENSE).
