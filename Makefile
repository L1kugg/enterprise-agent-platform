.PHONY: demo demo-verify demo-down demo-logs eval-demo package deploy-jar publish

package:
	mvn -DskipTests package -q

# jar 模式部署：本地打包并上传 jar（首次需先在服务器 mkdir -p /opt/knowledgeops-agent/target）
deploy-jar: package
	scp target/knowledgeops-agent-1.0-SNAPSHOT.jar root@82.157.60.115:/opt/knowledgeops-agent/target/

# 一键发布：jar + 前端源码打成一个包上传并重建容器（等同 bash scripts/publish.sh，输 2 次密码）
publish:
	bash scripts/publish.sh

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
