FROM node:slim

WORKDIR /usr/src/approuter
COPY ./ ./

RUN npm install

EXPOSE 7000
CMD [ "npm", "start" ]