# Change these if needed
$container = "finalexec-postgres"
$dbUser    = "finalexec"   # or "postgres"
$dbName    = "finalexec"

$sql = @"
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO public;
"@

# Execute SQL inside the container
$sql | docker exec -i $container psql -U $dbUser -d $dbName