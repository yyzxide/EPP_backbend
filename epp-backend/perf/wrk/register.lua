wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"

counter = 0

request = function()
  counter = counter + 1
  local ip_suffix = counter % 255
  local body = string.format(
    '{"deviceId":"perf-register-%d","osType":"linux","ipAddress":"10.0.1.%d"}',
    counter,
    ip_suffix
  )
  return wrk.format(nil, "/api/device/register", nil, body)
end
