param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$javaBin = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { Join-Path $env:JAVA_HOME 'bin' } else { Split-Path (Get-Command javac.exe).Source }
function Invoke-Jdk {
    param([string]$Tool, [string[]]$ToolArguments, [string]$Log)
    $executable = Join-Path $javaBin ($Tool + '.exe')
    if (!(Test-Path -LiteralPath $executable)) { $executable = (Get-Command ($Tool + '.exe')).Source }
    $result = & $executable @ToolArguments 2>&1
    $code = $LASTEXITCODE
    $result | Out-File -LiteralPath $Log -Encoding utf8
    $result | ForEach-Object { Write-Host $_ }
    if ($code -ne 0) { throw "$Tool failed ($code); see $Log" }
}
foreach ($folder in @('bin','test-bin','test-log','doc/api','resources','lib')) { New-Item -ItemType Directory -Path $folder -Force | Out-Null }
$utf8 = [Text.UTF8Encoding]::new($false)
$sources = @(Get-ChildItem -LiteralPath 'src/sgl' -Filter '*.java' | ForEach-Object { 'src/sgl/' + $_.Name })
[IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'test-log/sources.args'),$sources,$utf8)
Invoke-Jdk javac @('-J-Dfile.encoding=UTF-8','--release','17','-encoding','UTF-8','-Xlint:all,-serial','-Werror','-d','bin','@test-log/sources.args') 'test-log/build.log'
Invoke-Jdk javadoc @('-J-Dfile.encoding=UTF-8','--release','17','-encoding','UTF-8','-docencoding','UTF-8','-charset','UTF-8','-Xdoclint:all','-Werror','-quiet','-notimestamp','-windowtitle','Smart Grocery Ledger Java API','-doctitle','Smart Grocery Ledger Java API','-d','doc/api','@test-log/sources.args') 'test-log/javadoc.log'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apiArchive = Join-Path $PSScriptRoot 'resources/api.zip'
if (Test-Path -LiteralPath $apiArchive) { Remove-Item -LiteralPath $apiArchive }
[IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $PSScriptRoot 'doc/api'),$apiArchive)
Invoke-Jdk jar @('--create','--file','SmartGroceryLedger.jar','--main-class','sgl.Main','-C','bin','.','-C','resources','.') 'test-log/jar-build.log'
if (!$SkipTests) {
    Invoke-Jdk javac @('-J-Dfile.encoding=UTF-8','--release','17','-encoding','UTF-8','-Xlint:all,-serial','-Werror','-cp','bin','-d','test-bin','test/sgl/ServiceTests.java','test/sgl/UiSmokeTests.java','test/sgl/UiRenderEvidence.java') 'test-log/test-compile.log'
    Invoke-Jdk java @('-Dfile.encoding=UTF-8','-cp','bin;test-bin','sgl.ServiceTests') 'test-log/service-tests.log'
    Invoke-Jdk java @('-Dfile.encoding=UTF-8','-jar','SmartGroceryLedger.jar','--smoke-test') 'test-log/jar-smoke.log'
}
Write-Host 'Built SmartGroceryLedger.jar and doc/api/index.html (Java 17+)'
