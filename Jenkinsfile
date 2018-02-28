
def HELM_VERSION = "v2.6.1"

def appName = 'divolte'

node('jenkins-jenkins-slave') {
    def project = sh(
            script: 'curl --fail --silent http://metadata.google.internal/computeMetadata/v1/project/project-id -H "Metadata-Flavor: Google"',
            returnStdout: true
    ).trim()

    stage('Prepare helm') {
        // Install Helm
        sh """
        export
        curl -Lo /tmp/helm.tar.gz https://kubernetes-helm.storage.googleapis.com/helm-${HELM_VERSION}-linux-amd64.tar.gz
        tar -zxvf /tmp/helm.tar.gz -C /tmp
        mv /tmp/linux-amd64/helm /usr/local/bin/helm
        chmod +x /usr/local/bin/helm
        """
    }

    divolteImageName = "eu.gcr.io/${project}/${appName}"

    stage('Check out') {
        checkout scm
    }

    def hash = sh(
            script: 'git describe --always',
            returnStdout: true
    ).trim()

    if(env.BRANCH_NAME == "master") {
        stage('Create divolte image') {
            sh("docker build -t ${divolteImageName}:${hash} .")
            sh("gcloud docker -- push ${divolteImageName}:${hash}")
        }

        stage("Deploy Staging") {
            sh("helm upgrade --install --namespace intake-stag -f helm/divolte/values.yaml -f helm/divolte/values-stag.yaml --set image.name=${divolteImageName} --set image.tag=${hash} divolte-stag helm/divolte/")
        }
    }

    if (env.GIT_TAG_NAME) {
        stage('Create divolte image') {
            sh("docker build -t ${divolteImageName}:${env.GIT_TAG_NAME} .")
            sh("gcloud docker -- push ${divolteImageName}")
        }

        stage("Deploy production") {
            sh("helm upgrade --install --namespace intake-prod -f helm/divolte/values.yaml -f helm/divolte/values-prod.yaml --set image.name=${divolteImageName} --set image.tag=${env.GIT_TAG_NAME} divolte-prod helm/divolte/")
        }
    }
}
