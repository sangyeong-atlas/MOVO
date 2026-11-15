#!/bin/sh
# Set locally: API_BASE, TRIP_ID, VEHICLE_TOKEN, MEMBER_ID, REQUEST_ID.
# Never commit real token values. Pair response contains a secret; keep it private.
API_BASE=${API_BASE:-http://127.0.0.1:8787}
# Pair once (public one-time code, no host token):
# curl -s "$API_BASE/v2/pair/exchange" -H 'Content-Type: application/json' -d '{"code":"123456"}'
# Member states / per-member ETA:
curl --fail-with-body "$API_BASE/v2/trips/$TRIP_ID/snapshot" -H "Authorization: Bearer $VEHICLE_TOKEN"
# Real telemetry: replace observedAt with current UTC and sequence with next monotonic value.
# curl --fail-with-body "$API_BASE/v2/trips/$TRIP_ID/telemetry" -H "Authorization: Bearer $VEHICLE_TOKEN" -H 'Content-Type: application/json' --data-binary @telemetry.json
# Confirm boarding:
# curl --fail-with-body -X PATCH "$API_BASE/v2/trips/$TRIP_ID/members/$MEMBER_ID" -H "Authorization: Bearer $VEHICLE_TOKEN" -H 'Content-Type: application/json' -d '{"status":"boarded"}'
# Driver decision then SDK result (never send applied before actual success):
# curl --fail-with-body -X PATCH "$API_BASE/v2/trips/$TRIP_ID/requests/$REQUEST_ID" -H "Authorization: Bearer $VEHICLE_TOKEN" -H 'Content-Type: application/json' -d '{"status":"approved"}'
# curl --fail-with-body -X PATCH "$API_BASE/v2/trips/$TRIP_ID/requests/$REQUEST_ID" -H "Authorization: Bearer $VEHICLE_TOKEN" -H 'Content-Type: application/json' -d '{"status":"applied"}'
# Alternatives: {"status":"rejected","reason":"운전자 거절"} or after approval {"status":"failed","reason":"내비 SDK 실패"}
# Final parking sync: retry unchanged body with the SAME key until ACK.
# curl --fail-with-body -X PUT "$API_BASE/v2/trips/$TRIP_ID/parking" -H "Authorization: Bearer $VEHICLE_TOKEN" -H 'Content-Type: application/json' -H 'Idempotency-Key: final-parking-001' --data-binary @parking.json
