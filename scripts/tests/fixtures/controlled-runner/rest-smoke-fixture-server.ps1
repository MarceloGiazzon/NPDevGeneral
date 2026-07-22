param(
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$ReadyPath
)

$ErrorActionPreference = "Stop"

$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Start()
Set-Content -LiteralPath $ReadyPath -Value "ready" -Encoding UTF8

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $request = $context.Request
        $response = $context.Response
        $response.ContentType = "application/json"

        if ($request.HttpMethod -eq "GET" -and $request.Url.AbsolutePath -eq "/actuator/health") {
            $statusCode = 200
            $payload = [ordered]@{ status = "UP" }
        }
        elseif ($request.HttpMethod -eq "POST" -and $request.Url.AbsolutePath -in @("/api/users", "/api/learners")) {
            $reader = [System.IO.StreamReader]::new($request.InputStream, $request.ContentEncoding)
            $bodyText = $reader.ReadToEnd()
            $reader.Dispose()
            $body = if ([string]::IsNullOrWhiteSpace($bodyText)) { [pscustomobject]@{} } else { $bodyText | ConvertFrom-Json }
            $statusCode = 201
            if ($request.Url.AbsolutePath -eq "/api/learners") {
                $payload = [ordered]@{
                    id = "learner-1"
                    name = [string]$body.name
                    email = [string]$body.email
                }
            }
            else {
                $payload = [ordered]@{
                    id = "user-1"
                    email = [string]$body.email
                    displayName = [string]$body.displayName
                }
            }
        }
        else {
            $statusCode = 404
            $payload = [ordered]@{ error = "not-found" }
        }

        $json = $payload | ConvertTo-Json -Depth 10 -Compress
        $buffer = [System.Text.Encoding]::UTF8.GetBytes($json)
        $response.StatusCode = $statusCode
        $response.ContentLength64 = $buffer.Length
        $response.OutputStream.Write($buffer, 0, $buffer.Length)
        $response.OutputStream.Close()
    }
}
finally {
    $listener.Stop()
    $listener.Close()
}
