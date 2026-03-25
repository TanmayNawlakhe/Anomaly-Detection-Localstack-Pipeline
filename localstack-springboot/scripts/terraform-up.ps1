param(
  [string]$TerraformDir = "infra/terraform/localstack",
  [string]$VarFile = ""
)

$ErrorActionPreference = "Stop"

function Invoke-Terraform {
  param(
    [string[]]$TerraformArgs
  )

  $terraformCommand = Get-Command terraform -ErrorAction SilentlyContinue
  if ($terraformCommand) {
    & terraform @TerraformArgs
    return
  }

  $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
  if (-not $dockerCommand) {
    throw "Terraform CLI is not installed and Docker is unavailable. Install Terraform or Docker and retry."
  }

  $workspacePath = (Get-Location).Path
  $dockerArgs = @(
    "run", "--rm",
    "-v", "${workspacePath}:/workspace",
    "-e", "TF_VAR_aws_endpoint=http://host.docker.internal:4566",
    "-w", "/workspace",
    "hashicorp/terraform:1.9.8"
  ) + $TerraformArgs

  Write-Host "Terraform CLI not found. Using Dockerized Terraform (hashicorp/terraform:1.9.8)."
  & docker @dockerArgs
}

Push-Location $PSScriptRoot\..
try {
  $args = @("-chdir=$TerraformDir", "init")
  Invoke-Terraform -TerraformArgs $args

  $applyArgs = @("-chdir=$TerraformDir", "apply", "-auto-approve")
  if ($VarFile -and $VarFile.Trim().Length -gt 0) {
    $applyArgs += "-var-file=$VarFile"
  }
  Invoke-Terraform -TerraformArgs $applyArgs
}
finally {
  Pop-Location
}
