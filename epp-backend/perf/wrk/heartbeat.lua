wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"

device_count = 1000
counter = 0

request = function()
  counter = counter + 1
  local id = counter % device_count
  local ip_suffix = id % 255
  local body = string.format(
    '{"deviceId":"perf-heartbeat-%d","osType":"linux","ipAddress":"10.0.2.%d"}',
    id,
    ip_suffix
  )
  return wrk.format(nil, "/api/device/heartbeat", nil, body)
end
