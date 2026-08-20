# Set DeepSeek Authentication
$env:ANTHROPIC_API_KEY = "sk-c99807d35b7c4e1a8d805776d564e963"

# Path to the custom settings file created in Step 1
$SettingsPath = ".\claude-deepseek-settings.json"

# Launch Claude Code bypassing default settings
claude --settings "$SettingsPath" --bare