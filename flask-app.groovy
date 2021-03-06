def docker_registry = "sukhotin/flask-crud-app"
def docker_registry_creds = "dockerhub"
def docker_image = ""
def app_name = "flask-crud-app"
def app_port = "8181"
def mysql_service_name = "mysql.service.opsschool-project.consul"
def db_host = ""
def db_username = "app"
def db_password = "admin"
def db_name = "crud_flask"
def with_run_params = ""
def deployment = "deploy-flask-crud-app.yml"
def dns_name = ""
def s3_bucket = "su-opsschool-project"
def jmeter_test_plan = "project-test-plan.jmx"

pipeline {
    agent any 
    stages 
    {
        stage("Get MySql Server IP.")
        {
            steps 
            {
               script
               {
                   db_host = sh(returnStdout: true, script: "dig +short ${ mysql_service_name }").trim()
               }
            }
        }
        stage("Build a docker image.") {
            steps 
            {
                script 
                {
                    docker_image = docker.build(docker_registry)
                }
            }            
        }
        stage("Test the image.") 
        { 
            steps 
            {
                script 
                {
                    with_run_params = "-e db_host=${ db_host } -e db_username=${ db_username } -e db_password=${ db_password } -e db_name=${ db_name } -p ${ app_port }:${ app_port }"
                    docker.image(docker_registry).withRun(with_run_params) {c ->
                        sh "sleep 5"
                        sh "curl -sf -o /dev/null http://localhost:${app_port}"
                    }
                }
            }
        }
        stage("Push the image to DockerHub.") 
        { 
            steps 
            {
                script 
                {
                    withDockerRegistry(credentialsId: 'dockerhub', url: ''){
                        docker_image.push()
                    }
                }
            }
        }
        stage("Create configmap for app.") 
        { 
            steps 
            {
                script 
                {
                    
                    try
                    {
                        sh "kubectl get configmap ${ app_name }"
                    }
                    catch(exc)
                    {
                        sh "kubectl create configmap ${ app_name } --from-literal=db_host=${ mysql_service_name } --from-literal=db_name=${ db_name }"
                    }
                }
            }
        }
        stage("Create secrets for app.") 
        { 
            steps 
            {
                script 
                {
                    
                    try
                    {
                        sh "kubectl get secret ${ app_name }"
                    }
                    catch(exc)
                    {
                        sh "kubectl create secret generic ${ app_name } --from-literal=db_username=${ db_username } --from-literal=db_password=${ db_password }"
                    }
                }
            }
        }
        stage("Deploy app to k8s")
        {
            steps
            {
                script
                {
                    sh "kubectl apply -f ${ deployment }"
                    sh "sleep 5"
                    dns_name = sh(returnStdout: true, script:"kubectl get svc flask-crud-app -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'").trim()
                }
                timeout(time: 5, unit: 'MINUTES')
                {
                    sh "until \$(curl -s -o /dev/null --head --fail http://${ dns_name }); do printf 'Wait for ${ dns_name }...'; sleep 10; done"
                }
            }
        }
        stage("Make Load Test")
        {
            steps
            {
                script
                {
                    sh "cp ${jmeter_test_plan} ~/${jmeter_test_plan}"
                    sh "sed -i 's/%url%/${ dns_name }/g' ~/${ jmeter_test_plan }"
                    sh "/opt/jmeter/bin/jmeter -n -t ~/${ jmeter_test_plan } -l ~/load-test-result.csv"
                    sh "aws s3 cp ~/load-test-result.csv s3://${ s3_bucket }/load-test-result.csv"
                }
            }
        }
    }
}