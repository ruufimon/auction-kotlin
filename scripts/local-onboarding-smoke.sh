#!/usr/bin/env bash
# Smoke test of onboarding against the local Keycloak realm (deploy/keycloak/README.md).
# Needs: docker compose -f docker-compose.dev.yml up -d postgres keycloak mailpit; ./gradlew :app:run
set -u
KC=http://localhost:8180/realms/gavel/protocol/openid-connect/token
tok() { curl -s -d grant_type=password -d client_id=gavel-dev-cli -d "username=$1" -d "password=$2" -d scope=openid $KC; }
echo "== buyer token"; R=$(tok buyer@gavel.local dev-password-buyer); T=$(echo "$R" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("access_token",""))')
echo "$T" | cut -d. -f2 | python3 -c 'import sys,base64,json; s=sys.stdin.read().strip(); s+="="*(-len(s)%4); d=json.loads(base64.urlsafe_b64decode(s)); print({k:d.get(k) for k in ["iss","aud","email","email_verified","name"]})'
echo "== GET /api/v1/me anonymous"; curl -s -w ' [%{http_code}]\n' localhost:8080/api/v1/me
echo "== GET /api/v1/me with buyer token"; curl -s -w ' [%{http_code}]\n' -H "Authorization: Bearer $T" localhost:8080/api/v1/me
echo "== tampered token"; curl -s -w ' [%{http_code}]\n' -H "Authorization: Bearer ${T%?}x" localhost:8080/api/v1/me
echo "== unverified user password grant"; tok unverified@gavel.local dev-password-unverified; echo
echo "== wrong password"; tok buyer@gavel.local nope; echo
echo "== assessment"; U=$(curl -s -H "Authorization: Bearer $T" localhost:8080/api/v1/me | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d["userId"],d["version"])')
set -- $U
curl -s -w ' [%{http_code}]\n' -X PUT -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\":$2,\"assessment\":{\"age\":35,\"annualIncome\":6000000,\"netWealth\":3000000}}" localhost:8080/api/v1/profile/$1 | python3 -c 'import sys;s=sys.stdin.read();import re;print(re.sub(r"\"(phone|shippingAddress|sellerProfile|originAddress|createdAt|updatedAt|email|displayName)\":[^,]*,","",s))'
echo "== verification email via Keycloak"
A=$(curl -s -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin http://localhost:8180/realms/master/protocol/openid-connect/token | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')
KC_USER_ID=$(curl -s -H "Authorization: Bearer $A" "http://localhost:8180/admin/realms/gavel/users?email=unverified@gavel.local&exact=true" | python3 -c 'import json,sys;print(json.load(sys.stdin)[0]["id"])')
curl -s -o /dev/null -w 'send-verify-email [%{http_code}]\n' -X PUT -H "Authorization: Bearer $A" "http://localhost:8180/admin/realms/gavel/users/$KC_USER_ID/send-verify-email"
sleep 2; curl -s localhost:8025/api/v1/messages | python3 -c 'import json,sys;[print(m["To"][0]["Address"],"|",m["Subject"]) for m in json.load(sys.stdin)["messages"][:3]]'
