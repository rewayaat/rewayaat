#! /bin/bash

export PATH=$PATH:$HOME/.local/bin
echo "Pushing $IMAGE_NAME:$BUILD_NUMBER"
docker tag $IMAGE_NAME:latest "$REMOTE_IMAGE_URL:$BUILD_NUMBER"
docker push "$REMOTE_IMAGE_URL:$BUILD_NUMBER"
echo "Pushed $IMAGE_NAME:$BUILD_NUMBER"
