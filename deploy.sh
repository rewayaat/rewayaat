#! /bin/bash 

# Deploy only if it's not a pull request

if [ -z "$TRAVIS_PULL_REQUEST" ] || [ "$TRAVIS_PULL_REQUEST" == "false" ]; then

  # Deploy only if we're testing the master branch
  
  if [ "$TRAVIS_BRANCH" == "master" ]; then

     pip install --user awscli

     docker build -t $IMAGE_NAME .

     export PATH=$PATH:$HOME/.local/bin

     eval $(aws ecr get-login --region $AWS_DEFAULT_REGION)

      # Upload to docker hub

      docker login -u="$DOCKER_USERNAME" -p="$DOCKER_PASSWORD"

      echo "Pushing $IMAGE_NAME:$BUILD_NUMBER"

      docker tag $IMAGE_NAME:latest "$REMOTE_IMAGE_URL:$BUILD_NUMBER"

      docker push "$REMOTE_IMAGE_URL:$BUILD_NUMBER"

      docker tag $IMAGE_NAME:latest "$REMOTE_IMAGE_URL:latest"

      docker push "$REMOTE_IMAGE_URL:latest"

      echo "Pushed $IMAGE_NAME:$BUILD_NUMBER"

      # deploy to AWS

      echo "Deploying $GIT_LOCAL_BRANCH on $TASK_DEFINITION"

      ./ecs_deploy.sh --aws-instance-profile -c $CLUSTER -n $SERVICE -i $REMOTE_IMAGE_URL:latest -m 0 -v -M 100 -t 700
      
    else
    
      echo "Skipping deploy because it's not an allowed branch"
      
    fi
   
 else
 
  echo "Skipping deploy because it's a PR"
  
fi
