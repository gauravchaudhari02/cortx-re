#!/usr/bin/env groovy
pipeline { 
    agent {
        node {
            label "docker-${OS_VERSION}-node"
        }
    }

    options { 
        skipDefaultCheckout()
        timeout(time: 180, unit: 'MINUTES')
        timestamps()
        ansiColor('xterm')  
    }

    parameters {  
	    string(name: 'CSM_URL', defaultValue: 'https://github.com/Seagate/cortx-management-portal', description: 'Repo for CSM Web')
        string(name: 'CSM_BRANCH', defaultValue: 'main', description: 'Branch for CSM Web')   
	}

    environment {

        GPR_REPO = "https://github.com/${ghprbGhRepository}"
        CSM_URL = "${ghprbGhRepository != null ? GPR_REPO : CSM_URL}"
        CSM_BRANCH = "${sha1 != null ? sha1 : CSM_BRANCH}"

        CSM_GPR_REFSEPEC = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
        CSM_BRANCH_REFSEPEC = "+refs/heads/*:refs/remotes/origin/*"
        CSM_PR_REFSEPEC = "${ghprbPullId != null ? CSM_GPR_REFSEPEC : CSM_BRANCH_REFSEPEC}"

        //////////////////////////////// BUILD VARS //////////////////////////////////////////////////
        // OS_VERSION and COMPONENTS_BRANCH are manually created parameters in jenkins job.

        COMPONENT_NAME = "csm-web".trim()
        BRANCH = "${ghprbTargetBranch != null ? ghprbTargetBranch : COMPONENTS_BRANCH}"
        THIRD_PARTY_VERSION = "${OS_VERSION}-2.0.0-latest"
        VERSION = "2.0.0"
        RELEASE_TAG = "last_successful_prod"
        PASSPHARASE = credentials('rpm-sign-passphrase')

        // Artifacts root location

        // 'WARNING' - rm -rf command used on this path please careful when updating this value
        DESTINATION_RELEASE_LOCATION = "/mnt/bigstorage/releases/cortx/github/pr-build/${BRANCH}/${COMPONENT_NAME}/${BUILD_NUMBER}"
        PYTHON_DEPS = "/mnt/bigstorage/releases/cortx/third-party-deps/python-deps/python-packages-2.0.0-latest"
        THIRD_PARTY_DEPS = "/mnt/bigstorage/releases/cortx/third-party-deps/centos/${THIRD_PARTY_VERSION}/"
        COMPONENTS_RPM = "/mnt/bigstorage/releases/cortx/components/github/${BRANCH}/${OS_VERSION}/dev/"
        CORTX_BUILD = "http://cortx-storage.colo.seagate.com/releases/cortx/github/pr-build/${BRANCH}/${COMPONENT_NAME}/${BUILD_NUMBER}"

        // Artifacts location
        CORTX_ISO_LOCATION = "${DESTINATION_RELEASE_LOCATION}/cortx_iso"
        THIRD_PARTY_LOCATION = "${DESTINATION_RELEASE_LOCATION}/3rd_party"
        PYTHON_LIB_LOCATION = "${DESTINATION_RELEASE_LOCATION}/python_deps"

        ////////////////////////////////// DEPLOYMENT VARS /////////////////////////////////////////////////////

        STAGE_DEPLOY = "yes"
    }

    stages {

        // Build csm fromm PR source code
        stage('Build') {
            steps {
				script { build_stage = env.STAGE_NAME }
                script { manager.addHtmlBadge("&emsp;<b>Target Branch : ${BRANCH}</b>&emsp;<br />") }

                sh """
                    set +x
                    echo "--------------BUILD PARAMETERS -------------------"
                    echo "CSM_URL              = ${CSM_URL}"
                    echo "CSM_BRANCH           = ${CSM_BRANCH}"
                    echo "CSM_PR_REFSEPEC      = ${CSM_PR_REFSEPEC}"
                    echo "-----------------------------------------------------------"
                """
                 
                dir("csm") {

                    checkout([$class: 'GitSCM', branches: [[name: "${CSM_BRANCH}"]], doGenerateSubmoduleConfigurations: false,  extensions: [[$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cortx-admin-github', url: "${CSM_URL}",  name: 'origin', refspec: "${CSM_PR_REFSEPEC}"]]])

                    sh label: '', script: '''
                        yum-config-manager --add-repo=http://cortx-storage.colo.seagate.com/releases/cortx/github/$BRANCH/$OS_VERSION/$RELEASE_TAG/cortx_iso/
                        yum-config-manager --save --setopt=cortx-storage*.gpgcheck=1 cortx-storage* && yum-config-manager --save --setopt=cortx-storage*.gpgcheck=0 cortx-storage*
                        yum clean all && rm -rf /var/cache/yum
                        pip3.6 install  pyinstaller==3.5
                    '''

                    sh label: 'Build', returnStatus: true, script: '''
                        BUILD=$(git rev-parse --short HEAD)
                        echo "Executing build script"
                        echo "Python:$(python --version)"
                        ./cicd/build.sh $VERSION -b $BUILD_NUMBER -t
                    '''

                    // sh label: 'Collect Release Artifacts', script: '''
                    
                    //     rm -rf "${DESTINATION_RELEASE_LOCATION}"
                    //     mkdir -p "${DESTINATION_RELEASE_LOCATION}"
            
                    //     if [[ ( ! -z `ls /root/rpmbuild/RPMS/x86_64/*.rpm `)]]; then
                    //         mkdir -p "${CORTX_ISO_LOCATION}"
                    //         cp ./dist/rpmbuild/RPMS/x86_64/*.rpm "${CORTX_ISO_LOCATION}"
                    //     else
                    //         echo "RPM not exists !!!"
                    //         exit 1
                    //     fi
                    // '''	
                }
            }
        }

        // Release cortx deployment stack
        stage('Release') {
            when { expression { false } }
            steps {
				script { build_stage = env.STAGE_NAME }

                dir('cortx-re') {
                    checkout([$class: 'GitSCM', branches: [[name: '*/main']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', depth: 1, honorRefspec: true, noTags: true, reference: '', shallow: true], [$class: 'AuthorInChangelog']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cortx-admin-github', url: 'https://github.com/Seagate/cortx-re']]])
                }

                // Install tools required for release process
                 sh label: 'Installed Dependecies', script: '''
                    yum install -y expect rpm-sign rng-tools python3-pip
                    systemctl start rngd
                '''

                // Integrate components rpms
                sh label: 'Collect Release Artifacts', script: '''
                    
                    pushd ${COMPONENTS_RPM}
                        for component in `ls -1 | grep -E -v "${COMPONENT_NAME}"`
                        do
                            echo -e "Copying RPM's for $component"
                            if ls $component/last_successful/*.rpm 1> /dev/null 2>&1; then
                                cp $component/last_successful/*.rpm "${CORTX_ISO_LOCATION}"
                            fi
                        done
                    popd

                    # Symlink 3rdparty repo artifacts
                    ln -s "${THIRD_PARTY_DEPS}" "${THIRD_PARTY_LOCATION}"
                        
                    # Symlink python dependencies
                    ln -s "${PYTHON_DEPS}" "${PYTHON_LIB_LOCATION}"
                '''

                sh label: 'RPM Signing', script: '''
                    pushd cortx-re/scripts/rpm-signing
                        cat gpgoptions >>  ~/.rpmmacros
                        sed -i 's/passphrase/'${PASSPHARASE}'/g' genkey-batch
                        gpg --batch --gen-key genkey-batch
                        gpg --export -a 'Seagate'  > RPM-GPG-KEY-Seagate
                        rpm --import RPM-GPG-KEY-Seagate
                    popd

                    pushd cortx-re/scripts/rpm-signing
                        chmod +x rpm-sign.sh
                        cp RPM-GPG-KEY-Seagate ${CORTX_ISO_LOCATION}
                        for rpm in `ls -1 ${CORTX_ISO_LOCATION}/*.rpm`
                        do
                            ./rpm-sign.sh ${PASSPHARASE} ${rpm}
                        done
                    popd

                '''
                
                sh label: 'RPM Signing', script: '''
                    pushd ${CORTX_ISO_LOCATION}
                        rpm -qi createrepo || yum install -y createrepo
                        createrepo .
                    popd
                '''	

                sh label: 'RPM Signing', script: '''
                    pushd cortx-re/scripts/release_support
                        sh build_readme.sh "${DESTINATION_RELEASE_LOCATION}"
                        sh build_release_info.sh -v ${VERSION} -l ${CORTX_ISO_LOCATION} -t ${THIRD_PARTY_LOCATION}
                    popd

                    cp "${THIRD_PARTY_LOCATION}/THIRD_PARTY_RELEASE.INFO" "${DESTINATION_RELEASE_LOCATION}"
                    cp "${CORTX_ISO_LOCATION}/RELEASE.INFO" "${DESTINATION_RELEASE_LOCATION}"
                    cp "${CORTX_ISO_LOCATION}/RELEASE.INFO" .
                '''	

                archiveArtifacts artifacts: "RELEASE.INFO", onlyIfSuccessful: false, allowEmptyArchive: true 	
            }

        }
	}

    post {
        always {
            sh label: 'Remove artifacts', script: '''rm -rf "${DESTINATION_RELEASE_LOCATION}"'''
        }
        failure {
            script {
                manager.addShortText("${build_stage} Failed")
            }  
        }
    }
}