this is Generative AI project based on spring 25. WebFlux , Angular 21 (SSE) , Kafka , Redis , PostgresSQL , PgVector , Ollama , Oauth2 , SpringSecurity , Docker .

Pre Requirements : java 25 , NPM latest version , docker , maven , .swlconfig with (memory=6GB and processors=4)

things to do after project import :

run below command in project base folder 

1 : npm install
2 : mvn clean install
3 : docker compose up --build --no-cache -d
4 : docker ps (you must see all container running)

after this run :
docker exec -it ollama ollama pull nomic-embed-text
docker exec -it ollama ollama pull llama3.2:3b
docker exec -it ollama ollama list 

now you will see "nomic-embed-text" & "llama3.2:3" in the list.


please see below file for depth
[View the SpringAI Technical Architecture Documentation](./SpringAI_platform_documentation.pdf)


