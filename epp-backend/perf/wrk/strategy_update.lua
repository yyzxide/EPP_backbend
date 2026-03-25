wrk.method = "PUT"
wrk.headers["Content-Type"] = "application/json"

local token = os.getenv("EPP_ADMIN_TOKEN")
if token ~= nil and token ~= "" then
  wrk.headers["X-Admin-Token"] = token
end

counter = 0

request = function()
  counter = counter + 1
  local body = string.format(
    '{"configJson":"{\\"rules\\":\\"BLOCK\\",\\"version\\":%d}"}',
    counter
  )
  return wrk.format(nil, "/api/strategy/S1001", nil, body)
end
