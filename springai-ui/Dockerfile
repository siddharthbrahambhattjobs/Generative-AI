# Build stage
FROM node:22-alpine AS build
WORKDIR /workspace
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# Serve stage
FROM nginx:1.27-alpine
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
# Angular 21 build:application outputs to dist/springai-ui/browser/
COPY --from=build /workspace/dist/springai-ui/browser/ /usr/share/nginx/html/
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]