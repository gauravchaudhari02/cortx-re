pipeline {
    agent {
        node {
            label 'terraform-node'
        }
    }
    
    options {
        timeout(time: 30, unit: 'MINUTES')
        timestamps()
        buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '30'))
        ansiColor('xterm')
    }


    parameters {
        string(name: 'CORTX_RE_REPO', defaultValue: 'https://github.com/Seagate/cortx-re', description: 'Repository for VRA infra provisioner script', trim: true)
        string(name: 'CORTX_RE_BRANCH', defaultValue: 'main', description: 'Branch or GitHash for VRA infra provisioner script', trim: true)
        password(name: 'VRA_TOKEN', description: 'Token used to perform VRA operations. Refer link to generate token - https://seagate-systems.atlassian.net/wiki/spaces/PRIVATECOR/pages/1052672633/Access+Token+for+the+vRealize+Automation+VRA+API#1.-Refresh-Token')
        string(name: 'VM_NAMES', defaultValue: '', description: 'list of VM names need to be procured. (comma separated list of VM names)', trim: true)
        string(name: 'VRA_PROJECT', defaultValue: 'SSC-CICD', description: 'VRA Project under which deployment will be provisioned.', trim: true)
        string(name: 'VRA_CATALOG_ITEM', defaultValue: 'ssc-cicd-rocky', description: 'OS id required for VM', trim: true)
        string(name: 'VRA_CATALOG_ITEM_VERSION', defaultValue: '5', description: 'Version of OS id. Refer link to get version for catalog - https://seagate-systems.atlassian.net/wiki/spaces/PRIVATECOR/pages/1058210438/Create+VMs+Deployments#2.-List-the-available-versions', trim: true)
        choice(
            name: 'VM_CPU',
            choices: ['4', '2', '6', '8'],
            description: 'Number of vCPU required for VM'
        )
        choice(
            name: 'VM_MEMORY',
            choices: ['4096', '2048', '8192', '16384'],
            description: 'Memory required for VM'
        )
        choice(
            name: 'VM_DISKCOUNT',
            choices: ['4', '1', '2', '3', '5', '6'],
            description: 'Number of Disks required for VM'
        )
        choice(
            name: 'VM_DISKSIZE',
            choices: ['50', '25', '100', '150', '200', '250', '300'],
            description: 'Memory required for disks (In GB)'
        )
    }    

    stages {

        stage('Checkout Repository') {
            steps { 
                cleanWs()            
                script {
                    checkout([$class: 'GitSCM', branches: [[name: "${CORTX_RE_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[url: "${CORTX_RE_REPO}"]]])                
                }
            }
        }

        stage('Configure Terraform Environment') {
            steps {             
                script { build_stage = env.STAGE_NAME }
                sh label: '', script: '''
                    pushd solutions/vmware/terraform/
                        rm -rf .terraform .terraform.lock.hcl terraform.tfstate terraform.tfstate.backup terraform.tfvars
                        terraform init
                        if [ "$?" -ne 0 ]; then echo -e '\nERROR: Terraform Initialization Failed!!\n'; else echo -e '\n---SUCCESS---\n'; fi
                        QUOTED_VM_NAMES=$(jq -cR '. | gsub("^ +| +$"; "") | split(" *, *"; "")' <<< $VM_NAMES)
                        echo "vra_refresh_token = \\"$VRA_TOKEN\\"" >> terraform.tfvars
                        echo "vm_names = $QUOTED_VM_NAMES" >> terraform.tfvars
                        echo "vra_project = \\"$VRA_PROJECT\\"" >> terraform.tfvars
                        echo "vra_catalog_item = \\"$VRA_CATALOG_ITEM\\"" >> terraform.tfvars
                        echo "catalog_item_version = $VRA_CATALOG_ITEM_VERSION" >> terraform.tfvars
                        echo "vm_cpu = $VM_CPU" >> terraform.tfvars
                        echo "vm_memory = $VM_MEMORY" >> terraform.tfvars
                        echo "vm_disk_size = $VM_DISKCOUNT" >> terraform.tfvars
                        echo "vm_disk_count = $VM_DISKSIZE" >> terraform.tfvars
                    popd
                '''    
            }
        }

        stage ('Validate Infrastructure Configuration') {
            steps {
                script { build_stage = env.STAGE_NAME }
                sh label: '', script: '''
                    pushd solutions/vmware/terraform/
                        terraform validate
                        if [ "$?" -ne 0 ]; then echo -e '\nERROR: Validation Failed!!\n'; else echo -e '\n---SUCCESS---\n'; fi
                        terraform plan
                        if [ "$?" -ne 0 ]; then echo -e '\nERROR: Plan Failed!!\n'; else echo -e '\n---SUCCESS---\n'; fi
                    popd
                '''
            }
        }

        stage ('Provision Infrastructure') {
            steps {
                script { build_stage = env.STAGE_NAME }
                sh label: '', script: '''
                    pushd solutions/vmware/terraform/
                        terraform apply --auto-approve
                        if [ "$?" -ne 0 ]; then echo -e '\nERROR: Infra Provision Failed!!\n'; else echo -e '\n---SUCCESS---\n'; fi
                    popd
                '''
            }
        }
    }

    post {
        always {
            script {
                // Archive tfstate artifacts in jenkins build
                archiveArtifacts artifacts: "solutions/vmware/terraform//*.tfstate", onlyIfSuccessful: false, allowEmptyArchive: true
            }
        }
    }    
}