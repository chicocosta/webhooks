param(
    [string]$Emissor = 'http://localhost:8080',
    [string]$Receptor = 'http://localhost:8081'
)
$ErrorActionPreference = 'Stop'
$pedido = @{ customer = 'Ana'; total = 149.90 } | ConvertTo-Json
$entrega = Invoke-RestMethod "$Emissor/orders" -Method Post -ContentType 'application/json' -Body $pedido
if ($entrega.attempts[-1].httpStatus -ne 200) { throw 'Entrega falhou. Confira se o receptor esta ativo e os segredos coincidem.' }
$resposta = $entrega.attempts[-1].result | ConvertFrom-Json
if ($resposta.status -ne 'processed') { throw 'Esperado processed na primeira entrega.' }
Write-Host "Evento processado: $($entrega.event.id)"
$reenvio = Invoke-RestMethod "$Emissor/deliveries/$($entrega.event.id)/retry" -Method Post
$resposta = $reenvio.attempts[-1].result | ConvertFrom-Json
if ($resposta.status -ne 'duplicate') { throw 'Esperado duplicate no reenvio.' }
Write-Host 'Reenvio reconhecido como duplicado.'
Invoke-RestMethod "$Receptor/events" | ConvertTo-Json -Depth 10
