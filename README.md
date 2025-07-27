This is the minecraft plugin for the Coin Discord Bot And Coin WebSite (API)

This plugin is for minecraft 1.21+ and has the same api commands as the site:

/coin login username password
/coin register username password password
/coin pay nick amount
/coin bill nick amount
/coin paybill Bill_ID
/coin backup
/coin restore
/coin logout
/coin buy
/coin sell
/coin history
/coin bills
/coin baltop

And the accounts are saved in users folder from the Coin folder in plugins folder!

The config.yml is to setup the server and coins pairing values.

You can setup the server account in "owner" and the owner or OP Coin account ID in "ID" of the config file.

You can also setup the values of converting coins in minecraft vault money values.

The "cambio" value is the ammount of minecraft vault cash that you will receive from each coin sold.

The "reverse" is the amount you can receuve in coins from each vault cash sold.

The "card" is important to set because it's where the money will be going to be sent to the accounts that sells the minecraft money to receive coins.
And the account ID is the account "ID" where the coins are going to be send when someone buy minecraft vault cash using coins.

And the Owner is the vault account that the minecraft vault cash is settup as the server bank account to control the avaliable money to buy and sell using coins.

The API is the API url that the coin system is hosted. You can host your own coin system and use your API Link, but it's so expensive, you can use the Coin Default API instead.

Dependencies

You will need to download and install the plugins bellow for the Coin plugin to work.

- Vault
- PlaceholderAPI
- An Economy Plugin
- An Permissions plugin (recommend: LuckPerms)
- Spigot, Paper or other forks of Spigot Server .jar

And if you have all this... Just drop Coin.jar in the Plugins folder and restart your server!

Setup and use /coin reload and wow, you can finally use the plugin with your players!
