package com.droiddeck.launcher

object LogAnalyzer {

    fun analyze(logText: String): String {
        val lowerLog = logText.lowercase()

        return when {
            // 1. Проверяем память В ПЕРВУЮ ОЧЕРЕДЬ (как просил робот)
            lowerLog.contains("out of memory") || lowerLog.contains("enomem") -> {
                "❌ Не хватает оперативной памяти. Попробуйте закрыть другие приложения или увеличить лимит памяти."
            }
            // 2. Затем проверяем нехватку DLL
            lowerLog.contains("d3dx11_43.dll") || lowerLog.contains("d3dx9_43.dll") -> {
                "❌ Ошибка DirectX. Нажмите кнопку 'Установить зависимости' (DirectX), чтобы исправить это."
            }
            lowerLog.contains("msvcp140.dll") || lowerLog.contains("vcruntime140.dll") -> {
                "❌ Не хватает Visual C++ Redistributable. Установите VC++ 2015-2022 в контейнер."
            }
            lowerLog.contains("err:module:import_dll") -> {
                "❌ Отсутствует DLL-библиотека. Проверьте, установлены ли все необходимые компоненты."
            }
            // 3. Исправили текст ошибки exe (как просил робот)
            lowerLog.contains("bad exe format") -> {
                "❌ Ошибка формата исполняемого файла. Файл может быть поврежден или несовместим с системой."
            }
            else -> {
                "✅ Критических известных ошибок не найдено. Попробуйте сменить драйвер (Turnip/VirGL) или версию Wine."
            }
        }
    }
}
