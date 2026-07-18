// Jenkins pipeline for TactileSight's Android app.
// It runs the SAME steps we run by hand — inside the SAME Docker image — but automatically
// on every push. Requires the Jenkins "Docker Pipeline" plugin and Docker on the agent.
pipeline {

    // Build our isolated image from android/docker/Dockerfile and run every stage inside it,
    // so JDK 17 + Android SDK + Gradle are guaranteed. The named volume reuses the Gradle cache.
    agent {
        dockerfile {
            dir 'android/docker'
            reuseNode true
            args '-v ts-gradle:/root/.gradle'
        }
    }

    stages {
        stage('Unit tests') {
            steps {
                dir('android') {
                    sh 'gradle testDebugUnitTest --no-daemon --console=plain'
                }
            }
        }
        stage('Build APK') {
            steps {
                dir('android') {
                    sh 'gradle assembleDebug --no-daemon --console=plain'
                }
            }
        }
    }

    post {
        // Parse Gradle's JUnit XML so Jenkins shows pass/fail + trends in the UI.
        always {
            junit testResults: 'android/app/build/test-results/testDebugUnitTest/*.xml',
                  allowEmptyResults: true
        }
        // Keep the built APK as a downloadable artifact of a green build.
        success {
            archiveArtifacts artifacts: 'android/app/build/outputs/apk/debug/*.apk',
                             fingerprint: true
        }
    }
}
