# Run in an elevated PowerShell window.
$serviceName = 'REVisionWindowsAgent'
Stop-Service $serviceName -Force -ErrorAction SilentlyContinue
sc.exe delete $serviceName | Out-Null
Remove-NetFirewallRule -DisplayName 'RE:Vision Windows Agent (Private LAN)' -ErrorAction SilentlyContinue
Write-Host 'Service and firewall rule removed. Agent data in ProgramData was retained.'