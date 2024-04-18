#! /bin/bash

export PATH=$PATH:$HOME/.local/bin
echo "Pushing $IMAGE_NAME:$GITHUB_RUN_ID"
docker tag $IMAGE_NAME:latest "$REMOTE_IMAGE_URL:$GITHUB_RUN_ID"
docker push "$REMOTE_IMAGE_URL:$GITHUB_RUN_ID"
echo "Pushed $IMAGE_NAME:$GITHUB_RUN_ID"
