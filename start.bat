@echo off
title Compilador CoinSalary v1.0 - Java 17

echo ============================================
echo Compilador do Plugin CoinSalary (Integracao CoinCard)
echo ============================================
echo.

echo Procurando Java 17 instalado...
echo.

set JDK_PATH=

rem Procura JDK 17 em locais comuns
for /d %%i in ("C:\Program Files\Java\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Java\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\AdoptOpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\OpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Amazon Corretto\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Microsoft\jdk-17*") do set JDK_PATH=%%i

if "%JDK_PATH%"=="" (
    echo ============================================
    echo ERRO: JDK 17 nao encontrado!
    echo Instale o Java 17 JDK e tente novamente.
    echo ============================================
    pause
    exit /b 1
)

echo Java 17 encontrado em: %JDK_PATH%
echo.

set JAVAC="%JDK_PATH%\bin\javac.exe"
set JAR="%JDK_PATH%\bin\jar.exe"

echo ============================================
echo Preparando ambiente de compilacao...
echo ============================================
echo.

echo Limpando pasta out...
if exist out (
    rmdir /s /q out >nul 2>&1
)
mkdir out
mkdir out\com
mkdir out\com\foxsrv
mkdir out\com\foxsrv\coinsalary

echo.
echo ============================================
echo Verificando dependencias...
echo ============================================
echo.

REM Verificar Spigot API
if not exist spigot-api-1.20.1-R0.1-SNAPSHOT.jar (
    echo [ERRO] spigot-api-1.20.1-R0.1-SNAPSHOT.jar nao encontrado!
    echo.
    echo Certifique-se de que o arquivo está na pasta raiz.
    pause
    exit /b 1
) else (
    echo [OK] Spigot API encontrado
)

REM Verificar Gson
if not exist libs\gson-2.10.1.jar (
    echo [AVISO] gson-2.10.1.jar nao encontrado em libs\
    echo Tentando encontrar Gson em outro local...
    
    if exist gson-2.10.1.jar (
        echo [OK] Gson encontrado na pasta raiz
        set GSON_PATH=gson-2.10.1.jar
    ) else (
        echo [ERRO] Gson nao encontrado!
        echo.
        echo Certifique-se de que o arquivo gson-2.10.1.jar está em:
        echo   - libs\gson-2.10.1.jar
        echo   - ou na pasta raiz
        pause
        exit /b 1
    )
) else (
    echo [OK] Gson encontrado em libs\
    set GSON_PATH=libs\gson-2.10.1.jar
)

REM Verificar Vault API
if not exist Vault.jar (
    echo [AVISO] Vault.jar nao encontrado na pasta raiz!
    echo O plugin CoinSalary requer o Vault como dependencia.
    echo.
    echo Certifique-se de que o Vault.jar esta na pasta plugins do servidor.
    echo Continuando compilacao mesmo assim...
    echo.
    set VAULT_PATH=
) else (
    echo [OK] Vault API encontrado
    set VAULT_PATH=Vault.jar
)

REM Verificar CoinCard API
if not exist CoinCard.jar (
    echo [AVISO] CoinCard.jar nao encontrado na pasta raiz!
    echo O plugin CoinSalary requer o CoinCard como dependencia.
    echo.
    echo Certifique-se de que o CoinCard.jar esta na pasta plugins do servidor.
    echo Continuando compilacao mesmo assim...
    echo.
    set COINCARD_PATH=
) else (
    echo [OK] CoinCard API encontrado
    set COINCARD_PATH=CoinCard.jar
)

echo.
echo ============================================
echo Compilando CoinSalary...
echo ============================================
echo.

REM Montar classpath
set CLASSPATH="spigot-api-1.20.1-R0.1-SNAPSHOT.jar;%GSON_PATH%"
if defined VAULT_PATH (
    set CLASSPATH=%CLASSPATH%;%VAULT_PATH%
)
if defined COINCARD_PATH (
    set CLASSPATH=%CLASSPATH%;%COINCARD_PATH%
)

echo Classpath: %CLASSPATH%
echo.

REM Compilar com as dependências necessárias
%JAVAC% --release 17 -d out ^
-classpath %CLASSPATH% ^
-sourcepath src ^
src/com/foxsrv/coinsalary/CoinSalary.java

if %errorlevel% neq 0 (
    echo ============================================
    echo ERRO AO COMPILAR O PLUGIN!
    echo ============================================
    echo.
    echo Verifique os erros acima e corrija o codigo.
    pause
    exit /b 1
)

echo.
echo Compilacao concluida com sucesso!
echo.

echo ============================================
echo Copiando arquivos de recursos...
echo ============================================
echo.

REM Copiar plugin.yml
if exist resources\plugin.yml (
    copy resources\plugin.yml out\ >nul
    echo [OK] plugin.yml copiado
) else (
    echo [AVISO] plugin.yml nao encontrado em resources\
    echo Criando plugin.yml padrao...
    
    (
        echo name: CoinSalary
        echo version: 1.0
        echo main: com.foxsrv.coinsalary.CoinSalary
        echo api-version: 1.20
        echo author: FoxOficial2
        echo description: A Player Coin Salary plugin with CoinCard integration
        echo depend: [CoinCard, Vault]
        echo.
        echo commands:
        echo   salary:
        echo     description: Main CoinSalary command
        echo     aliases: [sal]
        echo     usage: /salary ^<reload^|next^|check^|pay^|group^|test^>
        echo   salaries:
        echo     description: List all salary groups
        echo     usage: /salaries
        echo.
        echo permissions:
        echo   coinsalary.*:
        echo     description: All CoinSalary permissions
        echo     default: op
        echo     children:
        echo       coinsalary.use: true
        echo       coinsalary.admin: true
        echo   coinsalary.use:
        echo     description: Use basic CoinSalary features (check own salary)
        echo     default: true
        echo   coinsalary.admin:
        echo     description: Admin commands (reload, pay, group, test)
        echo     default: op
    ) > out\plugin.yml
    echo [OK] plugin.yml criado automaticamente
)

REM Copiar config.yml
if exist resources\config.yml (
    copy resources\config.yml out\ >nul
    echo [OK] config.yml copiado
) else (
    echo [AVISO] config.yml nao encontrado em resources\
    echo Criando config.yml padrao...
    
    (
        echo # CoinSalary Configuration
        echo # Server Card ID for paying salaries
        echo Server: "e1301fadfc35"
        echo.
        echo # Cooldown between transactions in milliseconds
        echo Cooldown: 1100
        echo.
        echo # Rate in seconds between salaries
        echo Interval: 3600
        echo.
        echo # Whether or not to pay offline users
        echo offline: false
        echo.
        echo # Salary List
        echo # Groups are detected via Vault permission system
        echo # Players in multiple groups receive the sum of all salaries
        echo Groups:
        echo   default: 0.00000000
        echo   vip: 0.00000055
        echo   admin: 0.00100000
        echo   builder: 0.00050000
    ) > out\config.yml
    echo [OK] config.yml criado automaticamente
)

echo.
echo ============================================
echo Criando arquivo JAR...
echo ============================================
echo.

cd out

REM Criar JAR com todos os recursos
%JAR% cf CoinSalary.jar com plugin.yml config.yml

cd ..

echo.
echo ============================================
echo PLUGIN COMPILADO COM SUCESSO!
echo ============================================
echo.
echo Arquivo gerado: out\CoinSalary.jar
echo.
dir out\CoinSalary.jar
echo.
echo Tamanho do arquivo: 
for %%A in ("out\CoinSalary.jar") do echo %%~zA bytes
echo.
echo ============================================
echo IMPORTANTE - REQUISITOS PARA EXECUCAO:
echo ============================================
echo.
echo 1 - O plugin CoinCard DEVE estar instalado no servidor
echo 2 - O plugin Vault DEVE estar instalado no servidor
echo 3 - Um sistema de permissões (LuckPerms, GroupManager, etc.) deve estar instalado
echo.
echo ============================================
echo Para testar se as APIs estao disponiveis:
echo ============================================
echo.
echo No servidor, use:
echo   /plugman list (se tiver PlugMan)
echo   Deve mostrar: CoinCard, Vault, e seu sistema de permissões
echo.
echo ============================================
echo Para instalar:
echo ============================================
echo.
echo 1 - Copie out\CoinSalary.jar para a pasta plugins do servidor
echo 2 - Copie CoinCard.jar para a pasta plugins (se ainda nao estiver)
echo 3 - Copie Vault.jar para a pasta plugins (se ainda nao estiver)
echo 4 - Reinicie o servidor ou use /reload confirm
echo.
echo ============================================
echo COMANDOS DISPONIVEIS:
echo ============================================
echo.
echo /salaries                    - Lista todos os grupos de salario
echo /salary                      - Mostra ajuda
echo /salary check [jogador]      - Verifica salario de um jogador
echo /salary reload               - Recarrega configuracao (admin)
echo /salary next                  - Forca execucao da task agora (admin)
echo /salary pay <jogador>         - Paga salario manualmente (admin)
echo /salary group list            - Lista grupos configurados (admin)
echo /salary group <grupo> [valor] - Configura/remove grupo (admin)
echo /salary test <jogador>        - Testa grupos do jogador (admin)
echo.
echo ============================================
echo EXEMPLOS DE USO:
echo ============================================
echo.
echo /salary group vip 0.00000055
echo /salary group admin 0.00100000
echo /salary group test remove
echo /salary check player1
echo /salary pay player1
echo /salary next
echo /salaries
echo.
echo ============================================
echo.

pause