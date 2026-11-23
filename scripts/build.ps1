#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Root = Split-Path -Parent $PSScriptRoot
$Version = "1.0.0"
$ModId = "gen5w-digital-control"
$ModDisplayName = "Digital Control+"

function Resolve-ExistingPath([string[]]$Candidates, [string]$Label) {
    foreach ($c in $Candidates) {
        if ($c -and (Test-Path $c)) {
            return (Resolve-Path $c).Path
        }
    }
    throw "Could not locate $Label. Checked:`n  - $($Candidates -join "`n  - ")"
}

$WorkspaceParent = Split-Path -Parent $Root
$SiblingThemerTools = Join-Path $WorkspaceParent "Gen5WThemer\.build-tools"

$JdkBin = Resolve-ExistingPath @(
    $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin" } else { $null }),
    (Join-Path $Root ".build-tools\jdk\jdk-11.0.21+9\bin"),
    (Join-Path $SiblingThemerTools "jdk\jdk-11.0.21+9\bin")
) "JDK bin directory"

$Jar = Join-Path $JdkBin "jar.exe"
$Javac = Join-Path $JdkBin "javac.exe"
$Java = Join-Path $JdkBin "java.exe"
$Keytool = Join-Path $JdkBin "keytool.exe"

$AndroidJar = Resolve-ExistingPath @(
    $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platforms\android-28\android.jar" } else { $null }),
    (Join-Path $Root ".build-tools\android-sdk\platforms\android-28\android.jar"),
    (Join-Path $SiblingThemerTools "android-sdk\platforms\android-28\android.jar")
) "android-28 android.jar"

$Bt = Resolve-ExistingPath @(
    $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "build-tools\28.0.3" } else { $null }),
    (Join-Path $Root ".build-tools\android-sdk\build-tools\28.0.3"),
    (Join-Path $SiblingThemerTools "android-sdk\build-tools\28.0.3")
) "Android build-tools 28.0.3"

$Aapt = Join-Path $Bt "aapt.exe"
$D8Jar = Join-Path $Bt "lib\d8.jar"
$ZipAlign = Join-Path $Bt "zipalign.exe"
$ApkSignerJar = Join-Path $Bt "lib\apksigner.jar"

$FrameworkRes = Resolve-ExistingPath @(
    $env:GEN5W_FRAMEWORK_RES,
    (Join-Path $Root "oem\framework-res.apk"),
    (Join-Path $WorkspaceParent "Gen5WThemer\oem-dump\Gen5W_Theme_Dump\04_apks\framework\framework-res.apk")
) "stock Gen5W framework-res.apk (provides android:disableSS)"

$HmgApiRef = Resolve-ExistingPath @(
    $env:HMGAPI_REPO,
    (Join-Path $Root "..\HMG-API"),
    (Join-Path $WorkspaceParent "Gen5WCAN\reference\HMG-API")
) "HMG-API reference repository"

$BuildDir = Join-Path $Root "build"
$DistDir = Join-Path $Root "dist"
$KeysDir = Join-Path $Root "keys"

if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
if (Test-Path $DistDir) { Remove-Item -Recurse -Force $DistDir }
New-Item -ItemType Directory -Force -Path $BuildDir, $DistDir, $KeysDir | Out-Null

function Invoke-Checked([string]$Exe, [string[]]$ArgList, [string]$StepName) {
    Write-Host "[$StepName]" -ForegroundColor Cyan
    & $Exe @ArgList
    if ($LASTEXITCODE -ne 0) {
        throw "$StepName failed with exit code $LASTEXITCODE"
    }
}

function Write-Utf8Lf([string]$Path, [string]$Content) {
    $parent = Split-Path -Parent $Path
    if ($parent -and !(Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $normalized = $Content -replace "`r`n", "`n" -replace "`r", "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $normalized, $utf8NoBom)
}

function Copy-FileNormalizedLf([string]$Src, [string]$Dst) {
    $text = [System.IO.File]::ReadAllText($Src, [System.Text.Encoding]::UTF8)
    Write-Utf8Lf $Dst $text
}

function Create-ForwardSlashZip([string]$SourceDir, [string]$OutZip) {
    $outParent = Split-Path -Parent $OutZip
    if ($outParent -and !(Test-Path $outParent)) { New-Item -ItemType Directory -Force -Path $outParent | Out-Null }
    if (Test-Path $OutZip) { Remove-Item -Force $OutZip }
    $srcFull = (Resolve-Path $SourceDir).Path.TrimEnd('\', '/')
    $fs = [System.IO.File]::Open($OutZip, [System.IO.FileMode]::Create)
    $zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $files = Get-ChildItem -Path $srcFull -Recurse -File | Sort-Object FullName
        foreach ($f in $files) {
            $rel = $f.FullName.Substring($srcFull.Length + 1).Replace('\', '/')
            $entry = $zip.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
            $entryStream = $entry.Open()
            $inStream = [System.IO.File]::OpenRead($f.FullName)
            try {
                $inStream.CopyTo($entryStream)
            } finally {
                $inStream.Dispose()
                $entryStream.Dispose()
            }
        }
    } finally {
        $zip.Dispose()
        $fs.Dispose()
    }
}

# 1. Compile Shared Crypto/Protocol + HMG-API Client & Host Engine Tools
$SharedClasses = Join-Path $BuildDir "shared-classes"
$EngineClasses = Join-Path $BuildDir "hmgapi-engine"
New-Item -ItemType Directory -Force -Path $SharedClasses, $EngineClasses | Out-Null

$HmgClientSrc = Join-Path $HmgApiRef "core\client\java\hmgapi\client\HmgApi.java"
$SharedSrcFiles = Get-ChildItem (Join-Path $Root "shared\java") -Recurse -Filter "*.java" |
    Select-Object -ExpandProperty FullName

Invoke-Checked $Javac (@(
    "--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn",
    "-cp", $AndroidJar,
    "-d", $SharedClasses,
    $HmgClientSrc
) + $SharedSrcFiles) "Compile Shared Crypto/Protocol + HmgApi client"

$EngineSrcFiles = Get-ChildItem (Join-Path $HmgApiRef "core\runtime\java\hmgapi\engine") -Filter "*.java" |
    Select-Object -ExpandProperty FullName
Invoke-Checked $Javac (@(
    "--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn",
    "-cp", $AndroidJar,
    "-d", $EngineClasses
) + $EngineSrcFiles) "Compile HMG-API Engine tools"

# 2. Ensure Dedicated Signing Keys Exist in gitignored keys/
$CarKeystore = Join-Path $KeysDir "gen5w-digital-control-car.p12"
if (!(Test-Path $CarKeystore)) {
    Invoke-Checked $Keytool @(
        "-genkeypair", "-keystore", $CarKeystore, "-storetype", "PKCS12",
        "-storepass", "gen5wdigitalctl", "-keypass", "gen5wdigitalctl",
        "-alias", "carapp", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=Gen5W Digital Control Car"
    ) "Generate dedicated Car APK keystore"
}

$PhoneKeystore = Join-Path $KeysDir "gen5w-digital-control-phone.p12"
if (!(Test-Path $PhoneKeystore)) {
    Invoke-Checked $Keytool @(
        "-genkeypair", "-keystore", $PhoneKeystore, "-storetype", "PKCS12",
        "-storepass", "gen5wdigitalphone", "-keypass", "gen5wdigitalphone",
        "-alias", "phoneapp", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=Gen5W Digital Control Phone"
    ) "Generate dedicated Phone APK keystore"
}

$AuthorKey = Join-Path $KeysDir "gen5w-digital-control-author.key"
if (!(Test-Path $AuthorKey)) {
    Invoke-Checked $Java @("-cp", $EngineClasses, "hmgapi.engine.Signing", "keygen", $AuthorKey) "Generate HMG-API ECDSA author key"
}

Write-Host "`n================ BUILDING DIGITAL CONTROL+ $Version ================" -ForegroundColor Yellow

$ModStage = Join-Path $BuildDir "mod-stage"
$ModAppDir = Join-Path $ModStage "app"
$ModSvcDir = Join-Path $ModStage "svc"
New-Item -ItemType Directory -Force -Path $ModAppDir, $ModSvcDir | Out-Null

# A. Build Root Service JAR (svc/digital-control-svc.jar)
$SvcClasses = Join-Path $BuildDir "svc-classes"
New-Item -ItemType Directory -Force -Path $SvcClasses | Out-Null
$SvcSrc = Get-ChildItem (Join-Path $Root "car-svc\src\common") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName

Invoke-Checked $Javac (@(
    "--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn",
    "-cp", "$AndroidJar;$SharedClasses",
    "-d", $SvcClasses
) + $SvcSrc) "Compile ControlDaemon root service"

$SvcInputJar = Join-Path $BuildDir "svc-input.jar"
Invoke-Checked $Jar @("cf", $SvcInputJar, "-C", $SvcClasses, ".", "-C", $SharedClasses, ".") "Bundle ControlDaemon jar"
$OutSvcJar = Join-Path $ModSvcDir "digital-control-svc.jar"
Invoke-Checked $Java @(
    "-Xmx1024M", "-jar", $D8Jar,
    "--release", "--min-api", "19",
    "--lib", $AndroidJar,
    "--output", $OutSvcJar,
    $SvcInputJar
) "Dex ControlDaemon -> svc/digital-control-svc.jar"

# B. Build In-Car APK (minSdk 19, v1 JAR signed, android:disableSS=true)
$CarWork = Join-Path $BuildDir "car-app"
$CarGen = Join-Path $CarWork "gen"
$CarClasses = Join-Path $CarWork "classes"
$CarDex = Join-Path $CarWork "dex"
New-Item -ItemType Directory -Force -Path $CarGen, $CarClasses, $CarDex | Out-Null

$CarBaseApk = Join-Path $CarWork "base.apk"
$CarManifest = Join-Path $CarWork "AndroidManifest.xml"
Copy-Item (Join-Path $Root "car-app\AndroidManifest.xml") $CarManifest -Force
Invoke-Checked $Aapt @(
    "package", "-f", "-m",
    "-J", $CarGen,
    "-S", (Join-Path $Root "car-app\res"),
    "-M", $CarManifest,
    "-I", $AndroidJar,
    "-I", $FrameworkRes,
    "-F", $CarBaseApk
) "Package Car APK resources with aapt"

$CarSrc = @(Get-ChildItem (Join-Path $Root "car-app\src\common") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName) +
          @(Get-ChildItem $CarGen -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName)

Invoke-Checked $Javac (@(
    "--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn",
    "-cp", "$AndroidJar;$SharedClasses",
    "-d", $CarClasses
) + $CarSrc) "Compile Car APK Java"

$CarInputJar = Join-Path $CarWork "car-input.jar"
Invoke-Checked $Jar @("cf", $CarInputJar, "-C", $CarClasses, ".", "-C", $SharedClasses, ".") "Bundle Car APK classes"
Invoke-Checked $Java @(
    "-Xmx1024M", "-jar", $D8Jar,
    "--release", "--min-api", "19",
    "--lib", $AndroidJar,
    "--output", $CarDex,
    $CarInputJar
) "Dex Car APK -> classes.dex"

$CarUnsigned = Join-Path $CarWork "unsigned.apk"
$CarAligned = Join-Path $CarWork "aligned.apk"
Copy-Item $CarBaseApk $CarUnsigned -Force
$cz = [System.IO.Compression.ZipFile]::Open($CarUnsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $de = $cz.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $ds = $de.Open()
    $ss = [System.IO.File]::OpenRead((Join-Path $CarDex "classes.dex"))
    try { $ss.CopyTo($ds) } finally { $ss.Dispose(); $ds.Dispose() }
} finally {
    $cz.Dispose()
}

Invoke-Checked $ZipAlign @("-f", "4", $CarUnsigned, $CarAligned) "Zipalign Car APK"
$OutCarApk = Join-Path $ModAppDir "Gen5WDigitalControl.apk"
Invoke-Checked $Java @(
    "-Xmx1024M", "-jar", $ApkSignerJar,
    "sign", "--ks", $CarKeystore, "--ks-pass", "pass:gen5wdigitalctl",
    "--min-sdk-version", "19",
    "--v1-signing-enabled", "true", "--v2-signing-enabled", "false",
    "--out", $OutCarApk, $CarAligned
) "Sign Car APK (v1 JAR scheme for KitKat)"

# C. Sign HMG-API Mod & Wrap in NEMESIS USB Installer
Copy-FileNormalizedLf (Join-Path $Root "mod\mod.json") (Join-Path $ModStage "mod.json")
Copy-FileNormalizedLf (Join-Path $Root "mod\settings.json") (Join-Path $ModStage "settings.json")

Invoke-Checked $Java @("-cp", $EngineClasses, "hmgapi.engine.Signing", "sign", $ModStage, $AuthorKey) "Sign HMG-API mod"
Invoke-Checked $Java @("-cp", $EngineClasses, "hmgapi.engine.Signing", "verify", $ModStage) "Verify HMG-API mod signature"

$ModZip = Join-Path $DistDir "$ModId-$Version.zip"
Create-ForwardSlashZip $ModStage $ModZip

$NemesisPkgId = "hmgapi.$ModId"
$NemStage = Join-Path $BuildDir "nemesis-stage"
$NemScripts = Join-Path $NemStage "scripts"
$NemBin = Join-Path $NemStage "bin"
$NemMod = Join-Path $NemStage "mod"
New-Item -ItemType Directory -Force -Path $NemScripts, $NemBin, $NemMod | Out-Null

$TemplateIn = [System.IO.File]::ReadAllText((Join-Path $HmgApiRef "install\nemesis\mod\NEMESIS.data.in"), [System.Text.Encoding]::UTF8)
$Descriptor = $TemplateIn.
    Replace("@PACKAGE_ID@", $NemesisPkgId).
    Replace("@NAME@", $ModDisplayName).
    Replace("@PACKAGE_VERSION@", $Version).
    Replace("@SHORT_DESC@", "HMG-API mod: $ModDisplayName").
    Replace("@DETAILED_DESC@", "$ModDisplayName $Version (needs HMG-API). Companion phone Bluetooth & optional self-hosted E2EE Wi-Fi car controls, Bluelink remote climate, AC Profiles, and EV preconditioning & charge monitor.").
    Replace("@OS@", "any").
    Replace("@DEPENDS@", "hmgapi@>=1.0")
Write-Utf8Lf (Join-Path $NemStage "NEMESIS.data") $Descriptor

foreach ($s in @("preinstall.sh", "postinstall.sh", "preuninstall.sh")) {
    Copy-FileNormalizedLf (Join-Path $HmgApiRef "install\nemesis\mod\scripts\$s") (Join-Path $NemScripts $s)
}
Copy-FileNormalizedLf (Join-Path $Root "mod\nemesis\bin\open.sh") (Join-Path $NemBin "open.sh")
Write-Utf8Lf (Join-Path $NemMod "id") "$ModId`n"
Copy-Item $ModZip (Join-Path $NemMod "package.zip") -Force

$UnitCmds = Join-Path $HmgApiRef "core\runtime\unit-commands.txt"
$AllScripts = Get-ChildItem $NemStage -Recurse -Filter "*.sh" | Select-Object -ExpandProperty FullName
Invoke-Checked $Java (@("-cp", $EngineClasses, "hmgapi.engine.ScriptCheck", $UnitCmds) + $AllScripts) "Check shell scripts for Gen5W mksh/toolbox"

$NemesisPkgZip = Join-Path $DistDir "$ModId-$Version-nemesis-package.zip"
Create-ForwardSlashZip $NemStage $NemesisPkgZip

$StickStage = Join-Path $BuildDir "nemesis-stick"
$StickPkgs = Join-Path $StickStage "packages"
New-Item -ItemType Directory -Force -Path $StickPkgs | Out-Null
Copy-Item $NemesisPkgZip (Join-Path $StickPkgs "$NemesisPkgId.zip") -Force
Write-Utf8Lf (Join-Path $StickStage "NEMESIS.data") "[nemesis]`nversion=1.0`ntype=install`npath=packages/$NemesisPkgId.zip`n"

$ReadmeIn = [System.IO.File]::ReadAllText((Join-Path $HmgApiRef "install\nemesis\mod\README.txt"), [System.Text.Encoding]::UTF8)
$ReadmeOut = $ReadmeIn.
    Replace("@NAME@", $ModDisplayName).
    Replace("@VERSION@", $Version).
    Replace("@PACKAGE_ID@", $NemesisPkgId)
Write-Utf8Lf (Join-Path $StickStage "README.txt") $ReadmeOut

$NemesisUsbZip = Join-Path $DistDir "$ModId-$Version-nemesis.zip"
Create-ForwardSlashZip $StickStage $NemesisUsbZip

# D. Build Android Companion Phone APK (minSdk 24, v1 + v2 signed)
$PhWork = Join-Path $BuildDir "phone-app"
$PhGen = Join-Path $PhWork "gen"
$PhClasses = Join-Path $PhWork "classes"
$PhDex = Join-Path $PhWork "dex"
New-Item -ItemType Directory -Force -Path $PhGen, $PhClasses, $PhDex | Out-Null

$PhBaseApk = Join-Path $PhWork "base.apk"
$PhManifest = Join-Path $PhWork "AndroidManifest.xml"
Copy-Item (Join-Path $Root "phone-android\AndroidManifest.xml") $PhManifest -Force
Invoke-Checked $Aapt @(
    "package", "-f", "-m",
    "-J", $PhGen,
    "-S", (Join-Path $Root "phone-android\res"),
    "-M", $PhManifest,
    "-I", $AndroidJar,
    "-F", $PhBaseApk
) "Package Android Phone APK resources"

$PhSrc = @(Get-ChildItem (Join-Path $Root "phone-android\src\common") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName) +
         @(Get-ChildItem $PhGen -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName)

Invoke-Checked $Javac (@(
    "--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn",
    "-cp", "$AndroidJar;$SharedClasses",
    "-d", $PhClasses
) + $PhSrc) "Compile Android Phone APK Java"

$PhInputJar = Join-Path $PhWork "phone-input.jar"
Invoke-Checked $Jar @("cf", $PhInputJar, "-C", $PhClasses, ".", "-C", $SharedClasses, ".") "Bundle Android Phone APK classes"
Invoke-Checked $Java @(
    "-Xmx1024M", "-jar", $D8Jar,
    "--release", "--min-api", "24",
    "--lib", $AndroidJar,
    "--output", $PhDex,
    $PhInputJar
) "Dex Android Phone APK -> classes.dex"

$PhUnsigned = Join-Path $PhWork "unsigned.apk"
$PhAligned = Join-Path $PhWork "aligned.apk"
Copy-Item $PhBaseApk $PhUnsigned -Force
$pz = [System.IO.Compression.ZipFile]::Open($PhUnsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $pde = $pz.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $pds = $pde.Open()
    $pss = [System.IO.File]::OpenRead((Join-Path $PhDex "classes.dex"))
    try { $pss.CopyTo($pds) } finally { $pss.Dispose(); $pds.Dispose() }
} finally {
    $pz.Dispose()
}

Invoke-Checked $ZipAlign @("-f", "4", $PhUnsigned, $PhAligned) "Zipalign Android Phone APK"
$OutPhoneApk = Join-Path $DistDir "DigitalControlPlus-Phone-$Version.apk"
Invoke-Checked $Java @(
    "-Xmx1024M", "-jar", $ApkSignerJar,
    "sign", "--ks", $PhoneKeystore, "--ks-pass", "pass:gen5wdigitalphone",
    "--min-sdk-version", "24",
    "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
    "--out", $OutPhoneApk, $PhAligned
) "Sign Android Phone APK (v1 + v2)"

# E. Verification Checks
Write-Host "[Verify Artifacts & Self-Hostable Defaults]" -ForegroundColor Cyan
$CarXml = & $Aapt dump xmltree $OutCarApk AndroidManifest.xml | Out-String
if ($CarXml -notmatch "0x0101040f.*0xffffffff") {
    throw "Car APK missing android:disableSS=true (0x0101040f)"
}
$PhXml = & $Aapt dump xmltree $OutPhoneApk AndroidManifest.xml | Out-String
if ($CarXml -notmatch "android\.permission\.INTERNET" -or $PhXml -notmatch "android\.permission\.INTERNET") {
    throw "Car/Phone APK missing INTERNET permission for optional self-hosted server!"
}

$CarDexText = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes((Join-Path $CarDex "classes.dex")))
$PhDexText = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes((Join-Path $PhDex "classes.dex")))
if ($CarDexText.Contains("ioniqthemes") -or $PhDexText.Contains("ioniqthemes")) {
    throw "Found hardcoded 'ioniqthemes' server URL in classes.dex — default server URL must be blank!"
}
if (-not ($CarDexText.Contains("CarRemoteRelayWorker") -and $PhDexText.Contains("PhoneRemoteClient"))) {
    throw "Missing E2EE remote relay client classes in unified build!"
}
Write-Host "ok  Verified unified build has E2EE relay support with blank/neutral default server URL"

Invoke-Checked $Java @("-Xmx512M", "-jar", $ApkSignerJar, "verify", "--min-sdk-version", "19", $OutCarApk) "Verify Car APK v1 signature"
Invoke-Checked $Java @("-Xmx512M", "-jar", $ApkSignerJar, "verify", "--min-sdk-version", "24", $OutPhoneApk) "Verify Phone APK v1+v2 signature"

foreach ($lfFile in (@(Get-ChildItem $NemStage -Recurse -Include "*.sh","NEMESIS.data","id") + @(Get-ChildItem $StickStage -Recurse -Include "NEMESIS.data","README.txt") + @(Get-ChildItem $ModStage -Filter "*.json"))) {
    $bytes = [System.IO.File]::ReadAllBytes($lfFile.FullName)
    if ($bytes -contains 13) { throw "CRLF found in $($lfFile.FullName)" }
}

# Host simulation via HMG-API Engine
$FakeRoot = Join-Path $BuildDir "host-sim"
New-Item -ItemType Directory -Force -Path "$FakeRoot\data\hmgapi\mods", "$FakeRoot\system\etc\permissions" | Out-Null
Write-Utf8Lf "$FakeRoot\props.txt" "ro.build.product=NEEV.USA.S5W_M.V015.012.260507`nro.build.version.release=4.4.2`nro.board.platform=tcc803x`n"
$prevRoot = $env:HMGAPI_ROOT
$prevHost = $env:HMGAPI_HOST
$prevProd = $env:HMGAPI_PRODUCT
try {
    $env:HMGAPI_ROOT = $FakeRoot
    $env:HMGAPI_HOST = "1"
    $env:HMGAPI_PRODUCT = "NEEV.USA.S5W_M.V015.012.260507"
    $instOut = & $Java -cp $EngineClasses hmgapi.engine.Engine install $ModZip 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Engine host install failed:`n$instOut" }
    $planOut = & $Java -cp $EngineClasses hmgapi.engine.Engine plan 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $planOut -match "(?m)^problem ") {
        throw "Engine host plan reported problems:`n$planOut"
    }
    Write-Host "ok  HMG-API Engine host install & plan clean for NEEV.USA.S5W_M.V015.012.260507"
} finally {
    $env:HMGAPI_ROOT = $prevRoot
    $env:HMGAPI_HOST = $prevHost
    $env:HMGAPI_PRODUCT = $prevProd
}

# 3. Compile & Run Host JVM Crypto, E2EE, SAS-4, BLE Chunking, Key Backup, & TLS Test Suite
$HostTestClasses = Join-Path $BuildDir "host-test-classes"
New-Item -ItemType Directory -Force -Path $HostTestClasses | Out-Null
$HostTestSrc = @(
    (Join-Path $HmgApiRef "core\runtime\java\hmgapi\engine\Json.java"),
    (Join-Path $Root "car-app\src\common\com\gen5w\digitalcontrol\edition\RemoteTlsHelper.java")
) + @(Get-ChildItem (Join-Path $Root "shared\java") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName) `
  + @(Get-ChildItem (Join-Path $Root "tests\host") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName)

Invoke-Checked $Javac (@(
    "--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn",
    "-d", $HostTestClasses
) + $HostTestSrc) "Compile Host Crypto & Protocol Test Suite"

Invoke-Checked $Java @("-cp", $HostTestClasses, "DigitalControlHostTest") "Run Host Crypto & Protocol Test Suite"

Write-Host "`nALL BUILDS & VERIFICATION TESTS SUCCEEDED!" -ForegroundColor Green
Get-ChildItem -Recurse -File $DistDir | ForEach-Object {
    Write-Host ("  -> " + $_.FullName.Substring($Root.Length + 1)) -ForegroundColor Green
}
