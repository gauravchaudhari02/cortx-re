#!/bin/bash
#
# Copyright (c) 2022 Seagate Technology LLC and/or its Affiliates
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# For any questions about this software or licensing,
# please email opensource@seagate.com or cortx-questions@seagate.com.
#

PRE="true"
REGISTRY="ghcr.io/seagate"
RELEASE_REPO_NAME="cortx-re"
RELEASE_REPO_OWNER="gauravchaudhari02"
BUILD_INFO="**Build Instructions**: https://github.com/Seagate/cortx-re/tree/main/solutions/community-deploy/cloud/AWS#cortx-build"
DEPLOY_INFO="**Deployment Instructions**: https://github.com/Seagate/cortx-re/blob/main/solutions/community-deploy/CORTX-Deployment.md"
SERVICES_VERSION_TITLE="**CORTX Services Release**: "
CORTX_IMAGE_TITLE="**CORTX Container Images**: "
CHANGESET_TITLE="**Changeset**: "
# get args
while getopts :t:v:c: option
do
        case "${option}"
                in
                t) TAG="$OPTARG";;
                v) SERVICES_VERSION="$OPTARG";;
                c) CHANGESET_URL="$OPTARG";;
        esac
done
if [[ -z "$TAG" || -z "$SERVICES_VERSION" || -z "$CHANGESET_URL" ]]; then
        echo "Usage: git-release [-t <tag>] [-v <services-version>] [-c <changeset file url>]"
        exit 1
fi

# function to fetch container id of latest container image
function get_container_id {
    cortx_container="$1"
    container_id=$(curl -s -H "Accept: application/vnd.github+json" -H "Authorization: token $GH_TOKEN" https://api.github.com/orgs/seagate/packages/container/$cortx_container/versions | jq '.[] | select(.metadata.container.tags[]=="2.0.0-latest") | .id')
    echo "$container_id"
}

# Install required packages (Required only if gh api used for GitHub Release)
# yum install -y https://github.com/cli/cli/releases/download/v2.14.3/gh_2.14.3_linux_amd64.rpm || { echo "ERROR: failed to install gh"; exit 1; } 
# yum install jq -y || { echo "ERROR: failed to install jq"; exit 1; } 

# set release content
CHANGESET=$( curl -ks $CHANGESET_URL | tail -n +2 | sed 's/"//g' )
CORTX_SERVER_IMAGE="[$REGISTRY/cortx-rgw:$TAG](https://github.com/$RELEASE_REPO_OWNER/$RELEASE_REPO_NAME/pkgs/container/cortx-rgw/$( get_container_id "cortx-rgw" )?tag=$TAG)"
CORTX_DATA_IMAGE="[$REGISTRY/cortx-data:$TAG](https://github.com/$RELEASE_REPO_OWNER/$RELEASE_REPO_NAME/pkgs/container/cortx-data/$( get_container_id "cortx-data" )?tag=$TAG)"
CORTX_CONTROL_IMAGE="[$REGISTRY/cortx-control:$TAG](https://github.com/$RELEASE_REPO_OWNER/$RELEASE_REPO_NAME/pkgs/container/cortx-control/$( get_container_id "cortx-control" )?tag=$TAG)"
IMAGES_INFO="| Image      | Location |\n|    :----:   |    :----:   |\n| cortx-server      | $CORTX_SERVER_IMAGE       |\n| cortx-data      | $CORTX_DATA_IMAGE      |\n| cortx-control      | $CORTX_CONTROL_IMAGE       |"
SERVICES_VERSION="[$SERVICES_VERSION](https://github.com/Seagate/cortx-k8s/releases/tag/$SERVICES_VERSION)"
MESSAGE="$CORTX_IMAGE_TITLE\n$IMAGES_INFO\n\n$SERVICES_VERSION_TITLE$SERVICES_VERSION\n\n$BUILD_INFO\n$DEPLOY_INFO\n\n$CHANGESET_TITLE\n\n${CHANGESET//$'\n'/\\n}"

API_JSON=$(printf '{"tag_name":"%s","name":"%s","body":"%s","prerelease":%s}' "$TAG" "$TAG" "$MESSAGE" "$PRE" )
# Other ways to create GitHub Release using gh api
# jq -n "$API_JSON" | gh api /repos/$RELEASE_REPO_OWNER/$RELEASE_REPO_NAME/releases --input - > api_response.html
# gh release create <tag-name> --repo seagate/cortx --notes-file CHANGESET.md -p
if curl --data "$API_JSON" -sif -H "Accept: application/json" -H "Authorization: token $GH_TOKEN" "https://api.github.com/repos/$RELEASE_REPO_OWNER/$RELEASE_REPO_NAME/releases" > api_response.html
then
    echo "https://github.com/$RELEASE_REPO_OWNER/$RELEASE_REPO_NAME/releases/tag/$TAG"
else
    echo "ERROR: curl command has failed. Please check API response for more details"
    exit 1
fi