const fs = require('fs');
const files = {
  'values-tr': '%1$s yüklendi 🚀',
  'values-de': '%1$s hochgeladen 🚀',
  'values-es': '%1$s subido 🚀',
  'values-fr': '%1$s téléchargé 🚀',
  'values-ko': '%1$s 업로드됨 🚀'
};
for (const [dir, str] of Object.entries(files)) {
  const p = `app/src/main/res/${dir}/strings.xml`;
  if (fs.existsSync(p)) {
    let content = fs.readFileSync(p, 'utf8');
    content = content.replace('</resources>', `    <string name="msg_file_uploaded">${str}</string>\n</resources>`);
    fs.writeFileSync(p, content);
  }
}
console.log('Done');
