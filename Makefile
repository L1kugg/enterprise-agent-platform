.PHONY: demo demo-verify demo-down demo-logs eval-demo package deploy-jar

package:
	mvn -DskipTests package -q

# jar 模式部署：本地打包并上传 jar（首次需先在服务器 mkdir -p /opt/knowledgeops-agent/target）
deploy-jar: package
	scp target/knowledgeops-agent-1.0-SNAPSHOT.jar root@82.157.60.115:/opt/knowledgeops-agent/target/

demo:
	./scripts/demo.sh

demo-verify:
	./scripts/demo.sh verify

demo-down:
	./scripts/demo.sh down

demo-logs:
	./scripts/demo.sh logs

eval-demo:
	python3 scripts/eval_demo.py
