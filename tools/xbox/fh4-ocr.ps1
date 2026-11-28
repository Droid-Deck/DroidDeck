param([string]$Path, [switch]$Boxes)
# Windows OCR for one screenshot, asked for by fh4-online-run.py.
#
# Two things here are load-bearing, both learned from runs that silently reported
# "no title" on a screen that plainly had one:
#
#   * GetFileFromPathAsync rejects a relative path, and the rejection surfaces as a
#     null task a line later rather than a clear error, so the path is resolved
#     first. Callers pass absolute paths now, but a bare name still works.
#   * the decode/recognise pair faults intermittently (a screenshot still being
#     flushed, an engine that drops a request), which used to yield an empty
#     string that the caller read as "the screen had no text". Retrying turns that
#     into either text or an explicit OCR_FAILED the caller can see.
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($WinRtTask, $ResultType) {
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.GetAwaiter().GetResult()
}

[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
[Windows.Storage.StorageFile, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null

$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if (-not $engine) { Write-Output "NO_OCR_ENGINE"; exit 1 }

$Path = (Resolve-Path -LiteralPath $Path).Path

$bitmap = $null
$result = $null
for ($attempt = 1; $attempt -le 5; $attempt++) {
    try {
        $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) ([Windows.Storage.StorageFile])
        $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
        $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
        if ($result) { break }
    } catch {
        $bitmap = $null
        $result = $null
    }
    Start-Sleep -Milliseconds 350
}

if (-not $result) { Write-Output "OCR_FAILED $Path"; exit 2 }

Write-Output "img=$($bitmap.PixelWidth)x$($bitmap.PixelHeight) lines=$($result.Lines.Count)"
foreach ($l in $result.Lines) {
    $minx = [double]::MaxValue; $miny = [double]::MaxValue; $maxx = 0.0; $maxy = 0.0
    foreach ($w in $l.Words) {
        $r = $w.BoundingRect
        if ($r.X -lt $minx) { $minx = $r.X }
        if ($r.Y -lt $miny) { $miny = $r.Y }
        if (($r.X + $r.Width) -gt $maxx) { $maxx = $r.X + $r.Width }
        if (($r.Y + $r.Height) -gt $maxy) { $maxy = $r.Y + $r.Height }
    }
    $cx = [int](($minx + $maxx) / 2); $cy = [int](($miny + $maxy) / 2)
    if ($Boxes) { "{0,5},{1,5} {2,4}x{3,-4} | {4}" -f $cx, $cy, [int]($maxx - $minx), [int]($maxy - $miny), $l.Text }
    else { $l.Text }
}
