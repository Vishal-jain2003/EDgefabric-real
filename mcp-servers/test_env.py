from dotenv import load_dotenv
import os

load_dotenv()

print('SONAR_URL:', os.getenv('SONAR_URL'))
print('GITLAB_URL:', os.getenv('GITLAB_URL'))
print('JENKINS_URL:', os.getenv('JENKINS_URL'))
print('JENKINS_USER:', os.getenv('JENKINS_USER'))
print('SONAR_TOKEN:', 'SET ✅' if os.getenv('SONAR_TOKEN') else 'MISSING ❌')
print('GITLAB_TOKEN:', 'SET ✅' if os.getenv('GITLAB_TOKEN') else 'MISSING ❌')
print('JENKINS_TOKEN:', 'SET ✅' if os.getenv('JENKINS_TOKEN') else 'MISSING ❌')
