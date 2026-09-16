#!/bin/bash

# Lanu - Terminal Log Watcher (Otomatik Hata Yakalama ve Anayasal Raporlama Sistemi)

COMMAND="${1:-gradle compileDebugKotlin}"
TIMESTAMP=$(date +"%d-%m-%Y %H:%M")
COMMIT_HASH=$(git rev-parse --short HEAD 2>/dev/null || echo "LOCAL")
LOG_FILE="terminal_run_log.tmp"

echo "🔍 [Watcher] Çalıştırılan Komut: '$COMMAND'..."
echo "🕒 [Watcher] Başlangıç Zamanı: $TIMESTAMP"
echo "--------------------------------------------------------"

# Run command and capture both stdout and stderr
eval "$COMMAND" > "$LOG_FILE" 2>&1
EXIT_CODE=$?

# Print captured log directly to terminal so developer sees output in real-time
cat "$LOG_FILE"

echo "--------------------------------------------------------"
echo "📊 [Watcher] Analiz Gerçekleştiriliyor..."

# Scan for common error patterns
ERROR_FOUND=0
if [ $EXIT_CODE -ne 0 ]; then
    ERROR_FOUND=1
fi

# Additional scan for compilation or linting issues inside logs
if grep -qi -E "failed|error:|exception|compilation error|unresolved reference|failed with an exception" "$LOG_FILE"; then
    ERROR_FOUND=1
fi

if [ $ERROR_FOUND -eq 1 ]; then
    echo "❌ [Watcher] HATA TESPİT EDİLDİ!"
    
    # Extract the main error message lines to show in the report
    ERR_SUMMARY=$(grep -i -E "error:|exception|failed" "$LOG_FILE" | head -n 8)
    if [ -z "$ERR_SUMMARY" ]; then
        ERR_SUMMARY="Detaylı hata kaydı terminal log dosyasından alınamadı. Lütfen tam çıktı geçmişini inceleyin."
    fi

    # Create the anayasa error report file if not exists
    REPORT_FILE="anayasa/TERMINAL_HATA_LOGLARI.md"
    if [ ! -f "$REPORT_FILE" ]; then
        echo "# 🚨 Terminal Hata Logları ve Analizleri" > "$REPORT_FILE"
        echo "Bu döküman, Terminal Log Watcher tarafından otomatik olarak yakalanan hataları ve çözüm yollarını barındırır." >> "$REPORT_FILE"
        echo "" >> "$REPORT_FILE"
    fi

    # Append to TERMINAL_HATA_LOGLARI.md
    echo "---" >> "$REPORT_FILE"
    echo "### 🚨 Hata Raporu - [$TIMESTAMP] ($COMMIT_HASH)" >> "$REPORT_FILE"
    echo "*   **Çalıştırılan Komut:** \`$COMMAND\`" >> "$REPORT_FILE"
    echo "*   **Durum:** BAŞARISIZ (Exit Code: $EXIT_CODE) ❌" >> "$REPORT_FILE"
    echo "*   **Özet Hata Çıktısı:**" >> "$REPORT_FILE"
    echo "\`\`\`" >> "$REPORT_FILE"
    echo "$ERR_SUMMARY" >> "$REPORT_FILE"
    echo "\`\`\`" >> "$REPORT_FILE"
    echo "*   **Geliştirici Önerisi:** Lütfen yukarıdaki dosya yollarını ve linter uyarılarını inceleyin. Kod dizim hatalarını veya tanımlanamayan sınıfları giderdikten sonra linter'i tekrar çalıştırın." >> "$REPORT_FILE"
    echo "" >> "$REPORT_FILE"

    # Update ISLEM_GUNLUGU.md
    echo "" >> anayasa/ISLEM_GUNLUGU.md
    echo "### [$TIMESTAMP] - Terminal Log Watcher Hata Bildirimi ($COMMIT_HASH)" >> anayasa/ISLEM_GUNLUGU.md
    echo "*   **İşlem:** \`$COMMAND\` komutu terminal izleyicisi gözetiminde çalıştırıldı." >> anayasa/ISLEM_GUNLUGU.md
    echo "*   **Amaç:** Geliştirme ve derleme süreci canlı takibi." >> anayasa/ISLEM_GUNLUGU.md
    echo "*   **Sonuç:** Hata tespit edildi ve \`anayasa/TERMINAL_HATA_LOGLARI.md\` belgesine işlendi. 🚨" >> anayasa/ISLEM_GUNLUGU.md

    # Update OZELLIK_TESTLERI_VE_SONUCLARI.md
    echo "" >> anayasa/OZELLIK_TESTLERI_VE_SONUCLARI.md
    echo "### 🚨 Terminal Log Watcher Hata Tespiti ($TIMESTAMP)" >> anayasa/OZELLIK_TESTLERI_VE_SONUCLARI.md
    echo "*   **Komut:** \`$COMMAND\`" >> anayasa/OZELLIK_TESTLERI_VE_SONUCLARI.md
    echo "*   **Test Sonucu:** BAŞARISIZ ❌" >> anayasa/OZELLIK_TESTLERI_VE_SONUCLARI.md
    echo "*   **Detaylar:** Hata log detayları anayasa klasöründeki hata kayıt günlüğüne otomatik yazıldı." >> anayasa/OZELLIK_TESTLERI_VE_SONUCLARI.md

    echo "🚨 [Watcher Warning] Hata günlüğü anayasaya işlendi! Detaylar için 'anayasa/TERMINAL_HATA_LOGLARI.md' dosyasını inceleyin."
else
    echo "✅ [Watcher] BAŞARILI! Herhangi bir derleme veya çalışma zamanı hatası tespit edilmedi."

    # Update ISLEM_GUNLUGU.md
    echo "" >> anayasa/ISLEM_GUNLUGU.md
    echo "### [$TIMESTAMP] - Terminal Log Watcher Başarılı Derleme ($COMMIT_HASH)" >> anayasa/ISLEM_GUNLUGU.md
    echo "*   **İşlem:** \`$COMMAND\` komutu çalıştırıldı ve izlendi." >> anayasa/ISLEM_GUNLUGU.md
    echo "*   **Sonuç:** Herhangi bir derleme veya derleyici hatası algılanmadı. Durum: BAŞARILI ✅" >> anayasa/ISLEM_GUNLUGU.md

    # Update OZELLIK_TESTLERI_VE_SONUCLARI.md
    echo "" >> anayasa/OZELLIK_TESTLERI_VE_SONUCLARI.md
    echo "### 💻 Terminal Log Watcher Başarılı Test/Build ($TIMESTAMP)" >> anayasa/OZELLIK_TESTLERI_VE_SONUCLARI.md
    echo "*   **Komut:** \`$COMMAND\`" >> anayasa/OZELLIK_TESTLERI_VE_SONUCLARI.md
    echo "*   **Test Sonucu:** BAŞARILI ✅" >> anayasa/OZELLIK_TESTLERI_VE_SONUCLARI.md
    echo "*   **Detaylar:** Terminal loglarında sıfır hata/uyarı." >> anayasa/OZELLIK_TESTLERI_VE_SONUCLARI.md
fi

# Clean up temporary run log
rm -f "$LOG_FILE"
exit $EXIT_CODE
