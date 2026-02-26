# CoinSalary - Plugin de Salário para Minecraft

![Version](https://img.shields.io/badge/version-1.2-blue)
![Spigot](https://img.shields.io/badge/spigot-1.13--1.20-orange)
![License](https://img.shields.io/badge/license-MIT-green)

## 📋 Descrição

**CoinSalary** é um plugin robusto e eficiente para servidores Minecraft que permite pagar salários periódicos em coins para jogadores, integrado com o sistema **CoinCard** e **Vault**. O plugin foi desenvolvido para ser totalmente assíncrono, evitando qualquer tipo de lag no servidor, mesmo com muitos jogadores offline.

### ✨ Características Principais

- 💰 **Pagamento automático** de salários em intervalos configuráveis
- 👥 **Suporte a múltiplos grupos** via Vault/Permissions
- 🔄 **Totalmente assíncrono** - sem lag no servidor
- 💾 **Cache inteligente** para jogadores offline
- 📊 **Comandos completos** para gerenciamento
- 🔌 **Integração nativa** com CoinCard
- 🎯 **Pagamento forçado** com `/salary next` (ignora cooldown)
- 📝 **Persistência de dados** - salva últimos pagamentos

## ⚙️ Dependências

### Obrigatórias
- **[CoinCard](https://github.com/FoxUshiha/CoinCard)** - Para transações de coins
- **[Vault](https://www.spigotmc.org/resources/vault.34315/)** - Para gerenciamento de permissões/grupos

### Recomendadas
- **LuckPerms** ou qualquer outro sistema de permissões compatível com Vault

## 📥 Instalação

1. Certifique-se de ter o **CoinCard** e **Vault** instalados no servidor
2. Baixe o arquivo `CoinSalary.jar` da [última release]([https://github.com/seu-repo/coinsalary/releases](https://github.com/FoxUshiha/CoinSalary))
3. Coloque o arquivo na pasta `plugins/` do seu servidor
4. Reinicie o servidor ou execute `/reload`
5. Configure o arquivo `plugins/CoinSalary/config.yml`

## 🔧 Configuração

### config.yml

```yaml
# CoinSalary Configuration
# Server Card ID for paying salaries
Server: "e1301fadfc35"

# Cooldown between transactions in milliseconds
Cooldown: 1100

# Interval in seconds between automatic salary payments
# Example: 10 = 10 seconds, 3600 = 1 hour
Interval: 3600

# Whether or not to pay offline users
offline: false

# Salary Groups Configuration
# Each group is checked via Vault permissions
# The amount is in coins (can be decimal)
Groups:
  default: 0.00000000
  vip: 0.00000055
  admin: 0.00100000
  builder: 0.00050000
  # Add more groups as needed:
  # moderator: 0.00020000
  # helper: 0.00010000
  # elite: 0.00200000
