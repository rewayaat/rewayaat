#! /bin/bash

 export PATH=$PATH:$HOME/.local/bin

 eval $(aws ecr get-login --region $AWS_DEFAULT_REGION)

  echo "Pushing $IMAGE_NAME:$BUILD_NUMBER"

  docker tag $IMAGE_NAME:latest "$REMOTE_IMAGE_URL:$BUILD_NUMBER"

  docker push "$REMOTE_IMAGE_URL:$BUILD_NUMBER"

  docker tag $IMAGE_NAME:latest "$REMOTE_IMAGE_URL:latest"

  docker push "$REMOTE_IMAGE_URL:latest"

  echo "Pushed $IMAGE_NAME:$BUILD_NUMBER"

  # deploy to AWS

  echo "Deploying $GIT_LOCAL_BRANCH on $TASK_DEFINITION"

  ./ecs_deploy.sh --aws-instance-profile -c $CLUSTER -n $SERVICE -i $REMOTE_IMAGE_URL:latest -m 0 -v -M 100 -t 700
