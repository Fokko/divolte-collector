#!/usr/bin/env bash

export MINIKUBE_WANTUPDATENOTIFICATION=false
export MINIKUBE_WANTREPORTERRORPROMPT=false
export MINIKUBE_HOME=$HOME
export CHANGE_MINIKUBE_NONE_USER=true
export KUBECONFIG=$HOME/.kube/config

minikube addons enable ingress

# Build the Divolte container twice, with the same code to simulate
# a rolling update

docker build --no-cache -t fokkodriesprong/divolte-test:v1 .
docker push fokkodriesprong/divolte-test:v1

docker build --no-cache -t fokkodriesprong/divolte-test:v2 .
docker push fokkodriesprong/divolte-test:v2

kubectl apply -f k8s/volume.yaml

kubectl apply -f k8s/divolte.yaml

export URL="http://minikube-divolte/csc-event?p=0%3Ajejuh70g%3Ac~K96e0rIFYLOwbqteqf618YORLVF_5N&s=0%3Ajejuh70g%3AHJPj5EBtPm3K6sQJevssPlIfRzZuWxHT&v=0%3Al_5A1Sks0kBcM7APxzD1ouTcNRUjnqiz&e=0%3Al_5A1Sks0kBcM7APxzD1ouTcNRUjnqiz0&c=jejuj2p3&n=f&f=f&l=http%3A%2F%2Fminikube-divolte%2F&i=140&j=nl&k=2&w=10l&h=46&t=pageView&x=41o68f"

while ! nc -z minikube-divolte 80; do
  echo "Divolte not yet available"
  sleep 0.1 # wait for 1/10 of the second before check again
done

set -B # enable brace expansion
for i in {1..10000}; do
    STATUSCODE=$(curl --silent --output /dev/null --write-out "%{http_code}" $URL)
    if test $STATUSCODE -ne 200; then
        echo "BOOM $STATUSCODE"
    fi

    n=$((i%100))
    if test $n -eq 0; then
        echo "Step $i"
    fi
done
