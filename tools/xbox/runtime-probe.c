#include <windows.h>
#include <stdio.h>

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    const char *modules[] = { "xgameruntime.dll", "winhttp.dll", "windows.web.dll", "twinapi.appcore.dll" };
    int failed = 0;
    for (unsigned i = 0; i < sizeof(modules) / sizeof(modules[0]); i++)
    {
        printf("loading %s\n", modules[i]);
        HMODULE module = LoadLibraryA(modules[i]);
        printf("%s loaded=%u error=%lu\n", modules[i], module != NULL, module ? 0ul : GetLastError());
        if (!module) failed = 1;
        if (module && i == 0)
        {
            FARPROC entry = GetProcAddress(module, "InitializeApiImplEx2");
            printf("InitializeApiImplEx2 present=%u\n", entry != NULL);
            if (!entry) failed = 1;
        }
    }
    return failed;
}
